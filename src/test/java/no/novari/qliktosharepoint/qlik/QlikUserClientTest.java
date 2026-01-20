package no.novari.qliktosharepoint.qlik;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.novari.qliktosharepoint.config.QlikProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class QlikUserClientTest {

    private MockWebServer server;
    private QlikUserClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        String base = server.url("/").toString(); // e.g. http://localhost:12345/

        QlikProperties props = new QlikProperties();
        props.setBaseUrl(base.substring(0, base.length() - 1));
        props.setApiToken("dummy-token");
        props.setUsersEndpoint("/api/v1/users");
        props.setAuditEndpoint("/api/v1/audits");
        props.setAuditDaysBack(400);

        client = new QlikUserClient(props, WebClient.builder(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void get2Users_filtersByAuditActivity() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "data": [
                        {"id":"u1","name":"User One"},
                        {"id":"u2","name":"User Two"}
                      ],
                      "links": {"self":{"href":"/api/v1/users"}}
                    }
                """));

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "data": [
                        {"id":"a1","userId":"u1","eventType":"com.qlik.user-session.begin","eventTime":"2026-01-12T13:02:08.291Z"}
                      ],
                      "links": {"self":{"href":"/api/v1/audits"}}
                    }
                """));

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "data": [],
                      "links": {"self":{"href":"/api/v1/audits"}}
                    }
                """));

        List<QlikUserDto> result = client.getAllUsers();

        assertThat(result).isNotNull();
        assertThat(result).extracting(QlikUserDto::getId).containsExactly("u1");
    }

    @Test
    void getXUsers_filtersByAuditActivity() {
        int nUsers = 1500;
        int nActive = 1250;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(usersJson(nUsers)));

        List<String> activeUserIds = new ArrayList<>();
        for (int i = 1; i <= nActive; i++) {
            activeUserIds.add("u" + i);
        }
        enqueueAuditPagesForActiveUsers(activeUserIds);

        List<QlikUserDto> result = client.getAllUsers();

        assertThat(result).isNotNull();
        assertThat(result).hasSize(nActive);

        assertThat(result).extracting(QlikUserDto::getId)
                .containsExactlyElementsOf(expectedUserIds(nActive));
    }


    @Test
    void getUsers_pagesUsersBy100_andFiltersByAuditActivity_byPercent() {
        int totalUsers = 3853;
        int pageSize = 100;
        int activePercent = 85;

        java.util.function.IntPredicate isActive =
                i -> (i * 100 / totalUsers) < activePercent;

        enqueueUserPages(totalUsers, pageSize);

        List<String> activeUserIds = new ArrayList<>();
        int expectedActive = 0;

        for (int i = 1; i <= totalUsers; i++) {
            if (isActive.test(i)) {
                expectedActive++;
                activeUserIds.add("u" + i);
            }
        }

        enqueueAuditPagesForActiveUsers(activeUserIds);

        List<QlikUserDto> result = client.getAllUsers();

        assertThat(result).isNotNull();
        assertThat(result).hasSize(expectedActive);

        assertThat(result).allMatch(u -> {
            int n = Integer.parseInt(u.getId().substring(1));
            return isActive.test(n);
        });
    }


    private static String usersJson(int nUsers) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"data\":[");
        for (int i = 1; i <= nUsers; i++) {
            if (i > 1) sb.append(",");
            sb.append("{\"id\":\"u").append(i).append("\",\"name\":\"User ").append(i).append("\"}");
        }
        sb.append("],\"links\":{\"self\":{\"href\":\"/api/v1/users\"}}}");
        return sb.toString();
    }

    private static List<String> expectedUserIds(int nActive) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= nActive; i++) {
            ids.add("u" + i);
        }
        return ids;
    }

    private void enqueueUserPages(int totalUsers, int pageSize) {
        int from = 1;
        while (from <= totalUsers) {
            int to = Math.min(from + pageSize - 1, totalUsers);
            boolean hasNext = to < totalUsers;

            server.enqueue(usersPageJson(from, to, hasNext));
            from = to + 1;
        }
    }

    private MockResponse usersPageJson(int from, int to, boolean hasNext) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"data\":[");
        for (int i = from; i <= to; i++) {
            if (i > from) sb.append(",");
            sb.append("{\"id\":\"u").append(i).append("\",\"name\":\"User ").append(i).append("\"}");
        }
        sb.append("],\"links\":{");
        sb.append("\"self\":{\"href\":\"/api/v1/users\"}");
        if (hasNext) {
            sb.append(",\"next\":{\"href\":\"/api/v1/users?limit=100&next=page")
                    .append(from)
                    .append("\"}");
        }
        sb.append("}}");

        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sb.toString());
    }

    private void enqueueAuditPagesForActiveUsers(List<String> activeUserIds) {
        int total = activeUserIds.size();
        int pages = (total + 100 - 1) / 100;

        for (int p = 0; p < pages; p++) {
            int from = p * 100;
            int to = Math.min(from + 100, total);

            List<String> pageUserIds = activeUserIds.subList(from, to);

            String nextHref = (p < pages - 1)
                    ? "/api/v1/audits?page=" + (p + 2)
                    : null;

            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(auditBulkJson(pageUserIds, nextHref)));
        }
    }

    private String auditBulkJson(List<String> userIds, String nextHref) {
        String eventTime = "2026-01-15T10:00:00Z"; // innenfor siste 90 dager, stabil i test

        String data = userIds.stream()
                .map(uid -> String.format(
                        "{\"userId\":\"%s\",\"eventTime\":\"%s\",\"eventType\":\"com.qlik.user-session.begin\"}",
                        uid, eventTime))
                .collect(Collectors.joining(","));

        if (nextHref != null) {
            return "{"
                    + "\"data\":[" + data + "],"
                    + "\"links\":{\"next\":{\"href\":\"" + nextHref + "\"}}"
                    + "}";
        }
        return "{"
                + "\"data\":[" + data + "]"
                + "}";
    }

}
