package no.novari.qliktosharepoint.service;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.User;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import no.novari.qliktosharepoint.cache.EntraCache;
import no.novari.qliktosharepoint.config.GraphProperties;
import no.novari.qliktosharepoint.service.GraphUserService;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import okio.Buffer;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GraphUserServiceTest {

    @Test
    void inviteGuestUser_returnsInvitedUser_andCaches() throws Exception {
        GraphServiceClient graph = Mockito.mock(GraphServiceClient.class, Mockito.RETURNS_DEEP_STUBS);
        EntraCache entraCache = Mockito.mock(EntraCache.class);

        GraphProperties graphProps = new GraphProperties();
        graphProps.setInviteRedirectUrl("https://example.com");

        ClientSecretCredential credential = Mockito.mock(ClientSecretCredential.class);
        OkHttpClient ok = Mockito.mock(OkHttpClient.class);
        ObjectMapper om = new ObjectMapper();

        // token mock
        when(credential.getToken(any(TokenRequestContext.class)))
                .thenReturn(Mono.just(new AccessToken("tok", OffsetDateTime.now().plusHours(1))));

        // OkHttp response mock (200 + invitedUser.id)
        String responseJson = """
            {"invitedUser":{"id":"guest-id"}}
        """;

        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://graph.microsoft.com/v1.0/invitations").build())
                .protocol(Protocol.HTTP_1_1)
                .code(201)
                .message("Created")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json")))
                .build();

        Call call = Mockito.mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(ok.newCall(any(Request.class))).thenReturn(call);

        GraphUserService svc = new GraphUserService(graph, graphProps, entraCache, credential, ok, om);

        User out = svc.inviteGuestUser("a@b.com", "A B");

        assertThat(out.getId()).isEqualTo("guest-id");
        verify(entraCache).putGuest("a@b.com", "guest-id");

        // Verifiser at vi faktisk POSTer til invitations med Authorization-header
        ArgumentCaptor<Request> reqCap = ArgumentCaptor.forClass(Request.class);
        verify(ok).newCall(reqCap.capture());

        Request sent = reqCap.getValue();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.url().toString()).isEqualTo("https://graph.microsoft.com/v1.0/invitations");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer tok");
        assertThat(sent.header("Accept")).isEqualTo("application/json");

        // Ikke lenger relevant:
        verify(graph, never()).invitations();
    }

    @Test
    void inviteGuestUser_invitesXUsers_andCachesAll() throws Exception {
        int userCount = 7;

        GraphServiceClient graph = Mockito.mock(GraphServiceClient.class, Mockito.RETURNS_DEEP_STUBS);
        EntraCache entraCache = Mockito.mock(EntraCache.class);

        GraphProperties graphProps = new GraphProperties();
        graphProps.setInviteRedirectUrl("https://example.com");

        ClientSecretCredential credential = Mockito.mock(ClientSecretCredential.class);
        OkHttpClient ok = Mockito.mock(OkHttpClient.class);
        ObjectMapper om = new ObjectMapper();

        when(credential.getToken(any(TokenRequestContext.class)))
                .thenReturn(Mono.just(new AccessToken("tok", OffsetDateTime.now().plusHours(1))));

        // Vi lager en "call" per request som returnerer forskjellig JSON basert på request-body
        when(ok.newCall(any(Request.class))).thenAnswer(inv -> {
            Request req = inv.getArgument(0);

            // vi må lese request-body (okhttp RequestBody kan skrives til Buffer)
            Buffer buf = new Buffer();
            assertThat(req.body()).isNotNull();
            req.body().writeTo(buf);
            String body = buf.readUtf8();

            // body inneholder invitedUserEmailAddress
            String email = extractEmailFromInviteBody(body);

            String responseJson = "{\"invitedUser\":{\"id\":\"guest-" + email + "\"}}";

            Response resp = new Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(ResponseBody.create(responseJson, MediaType.get("application/json")))
                    .build();

            Call c = Mockito.mock(Call.class);
            when(c.execute()).thenReturn(resp);
            return c;
        });

        GraphUserService svc = new GraphUserService(graph, graphProps, entraCache, credential, ok, om);

        for (int i = 1; i <= userCount; i++) {
            String email = "user" + i + "@example.com";
            String name = "User " + i;

            User out = svc.inviteGuestUser(email, name);

            assertThat(out).isNotNull();
            assertThat(out.getId()).isEqualTo("guest-" + email);
        }

        verify(ok, times(userCount)).newCall(any(Request.class));
        verify(graph, never()).invitations();

        for (int i = 1; i <= userCount; i++) {
            String email = "user" + i + "@example.com";
            verify(entraCache).putGuest(email, "guest-" + email);
        }
    }

    private static String extractEmailFromInviteBody(String jsonBody) {
        // finner "invitedUserEmailAddress":"...".
        int idx = jsonBody.indexOf("\"invitedUserEmailAddress\"");
        if (idx < 0) return null;

        int colon = jsonBody.indexOf(':', idx);
        int firstQuote = jsonBody.indexOf('"', colon + 1);
        int secondQuote = jsonBody.indexOf('"', firstQuote + 1);

        if (firstQuote < 0 || secondQuote < 0) return null;
        return jsonBody.substring(firstQuote + 1, secondQuote);
    }
}