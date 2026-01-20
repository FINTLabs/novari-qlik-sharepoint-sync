package no.novari.qliktosharepoint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.Invitation;
import com.microsoft.graph.models.User;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.cache.EntraCache;
import no.novari.qliktosharepoint.config.GraphProperties;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphUserService {

    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    private static final String INVITATIONS_URL = "https://graph.microsoft.com/v1.0/invitations";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final GraphServiceClient graphServiceClient;
    private final GraphProperties graphProperties;
    private final EntraCache entraCache;

    private final com.azure.identity.ClientSecretCredential graphCredential;
    private final OkHttpClient graphOkHttpClient;
    private final ObjectMapper objectMapper;

    public Optional<User> findGuestByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        String filter = "userType eq 'Guest' and mail eq '" + email.replace("'", "''") + "'";

        try {
            var page = graphServiceClient
                    .users()
                    .get(requestConfiguration -> {
                        requestConfiguration.queryParameters.filter = filter;
                        requestConfiguration.queryParameters.top = 1;
                    });

            if (page == null || page.getValue() == null || page.getValue().isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(page.getValue().get(0));

        } catch (ApiException e) {
            log.error("Error querying guest user by email {}: {}", email, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public String ensureGuestUserId(String email, String displayName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is blank");
        }

        String normalizedEmail = email.trim().toLowerCase();

        String cachedUserId = entraCache.getGuestIdByEmail(normalizedEmail);
        if (cachedUserId != null && !cachedUserId.isBlank()) {
            return cachedUserId;
        }

        User invited = inviteGuestUser(normalizedEmail, displayName);

        String userId = invited != null ? invited.getId() : null;
        if (userId == null || userId.isBlank()) {
            userId = findGuestByEmail(normalizedEmail)
                    .map(User::getId)
                    .orElse(null);
        }

        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("Could not resolve userId for invited guest: " + normalizedEmail);
        }

        entraCache.putGuest(normalizedEmail, userId);
        return userId;
    }

    public User inviteGuestUser(String email, String displayName) {
        return inviteGuestUserWithRetry(email, displayName);
    }

    private User inviteGuestUserWithRetry(String email, String displayName) {
        log.info("Inviting guest user {} ({}) via Graph (no email will be sent)", displayName, email);

        int maxAttempts = 8;
        long baseBackoffMs = 600;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return inviteOnceHttp(email, displayName);

            } catch (GraphThrottleOrTransientException te) {
                if (attempt == maxAttempts) {
                    log.error("Invite guest FAILED after retries email={} lastStatus={} lastBody={}",
                            email, te.statusCode, te.responseBody);
                    throw new RuntimeException("Invite guest failed after retries email=" + email, te);
                }

                long sleepMs = backoffMs(baseBackoffMs, attempt);
                if (te.retryAfterMs > 0) {
                    sleepMs = Math.max(sleepMs, te.retryAfterMs);
                }

                log.warn("Invite guest RETRY email={} attempt={}/{} status={} retryAfterMs={} sleepMs={}",
                        email, attempt, maxAttempts, te.statusCode, te.retryAfterMs, sleepMs);

                sleepQuietly(sleepMs);

            } catch (RuntimeException re) {
                throw re;
            }
        }

        throw new IllegalStateException("unreachable");
    }

    private User inviteOnceHttp(String email, String displayName) {
        String token = acquireToken();

        try {
            var body = objectMapper.createObjectNode();
            body.put("invitedUserEmailAddress", email);
            body.put("invitedUserDisplayName", displayName);
            body.put("inviteRedirectUrl", graphProperties.getInviteRedirectUrl());
            body.put("sendInvitationMessage", false);

            Request req = new Request.Builder()
                    .url(INVITATIONS_URL)
                    .post(RequestBody.create(body.toString(), JSON))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = graphOkHttpClient.newCall(req).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";

                if (resp.code() == 429 || resp.code() == 503 || resp.code() == 504 || (resp.code() >= 500 && resp.code() <= 599)) {
                    long raMs = parseRetryAfterMs(resp);
                    throw new GraphThrottleOrTransientException(resp.code(), raMs, respBody);
                }

                if (!resp.isSuccessful()) {
                    throw new RuntimeException("Invite guest failed status=" + resp.code() + " email=" + email + " body=" + respBody);
                }

                JsonNode json = objectMapper.readTree(respBody);

                JsonNode invitedUser = json.get("invitedUser");
                User user = new User();

                if (invitedUser != null && invitedUser.hasNonNull("id")) {
                    user.setId(invitedUser.get("id").asText());
                }

                if (user.getId() != null && !user.getId().isBlank()) {
                    entraCache.putGuest(email, user.getId());
                }

                return user;
            }

        } catch (IOException ioe) {
            throw new GraphThrottleOrTransientException(0, 0, "IO error: " + ioe.getMessage(), ioe);

        } catch (GraphThrottleOrTransientException te) {
            throw te;

        } catch (Exception e) {
            throw new RuntimeException("Invite guest failed (unexpected) email=" + email + " msg=" + e.getMessage(), e);
        }
    }

    private String acquireToken() {
        var tok = graphCredential.getToken(new com.azure.core.credential.TokenRequestContext().addScopes(GRAPH_SCOPE))
                .block(java.time.Duration.ofSeconds(30));
        if (tok == null || tok.getToken() == null || tok.getToken().isBlank()) {
            throw new IllegalStateException("Failed to acquire Graph token");
        }
        return tok.getToken();
    }

    private long parseRetryAfterMs(Response resp) {
        String ra = resp.header("Retry-After");
        if (ra == null || ra.isBlank()) return 0;

        ra = ra.trim();

        try {
            long sec = Long.parseLong(ra);
            return Math.max(0, sec) * 1000L;
        } catch (NumberFormatException ignore) {
        }

        try {
            ZonedDateTime when = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME);
            long ms = when.toInstant().toEpochMilli() - System.currentTimeMillis();
            return Math.max(0, ms);
        } catch (Exception ignore) {
        }

        return 0;
    }

    private long backoffMs(long baseMs, int attempt) {
        long exp = baseMs * (1L << Math.min(6, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, 400);
        return Math.min(45_000, exp + jitter);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static class GraphThrottleOrTransientException extends RuntimeException {
        final int statusCode;
        final long retryAfterMs;
        final String responseBody;

        GraphThrottleOrTransientException(int statusCode, long retryAfterMs, String responseBody) {
            super("Graph retryable status=" + statusCode);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
            this.responseBody = responseBody;
        }

        GraphThrottleOrTransientException(int statusCode, long retryAfterMs, String responseBody, Throwable cause) {
            super("Graph retryable status=" + statusCode, cause);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
            this.responseBody = responseBody;
        }
    }
}
