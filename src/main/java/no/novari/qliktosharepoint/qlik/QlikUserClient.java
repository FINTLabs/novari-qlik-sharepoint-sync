package no.novari.qliktosharepoint.qlik;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.config.QlikProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

@Slf4j
@Component
public class QlikUserClient {

    private static final String SESSION_BEGIN = "com.qlik.user-session.begin";
    private static final int USERS_LIMIT = 100;
    private static final int LOG_TRUNCATE = 800;

    private static final DateTimeFormatter EVENT_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final WebClient webClient;
    private final QlikProperties properties;
    private final ObjectMapper objectMapper;

    public QlikUserClient(QlikProperties properties, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    public List<QlikUserDto> getAllUsers() {
        List<QlikUserDto> allUsers = new ArrayList<>();
        String nextUrl = properties.getUsersEndpoint() + "?limit=" + USERS_LIMIT;

        while (nextUrl != null) {
            QlikUserListResponse page = fetchUsersPage(nextUrl);
            if (page == null) {
                log.warn("Qlik USERS fetch failed - returning null to signal failure");
                return null;
            }

            if (page.getData() != null && !page.getData().isEmpty()) {
                allUsers.addAll(page.getData());
                log.debug("Fetched {} users, total so far {}", page.getData().size(), allUsers.size());
            } else {
                log.warn("No users in response from Qlik for URL {}", nextUrl);
            }

            nextUrl = nextHrefOrNull(page.getLinks());
        }

//        Integer daysBackCfg = properties.getAuditDaysBack();
        int daysBack = properties.getAuditDaysBack();
//        Integer daysBackCfg = properties.getAuditDaysBack();
//        int daysBack = (daysBackCfg != null && daysBackCfg > 0) ? daysBackCfg : 400;

        try {
            int before = allUsers.size();

            LocalDate to = LocalDate.now(ZoneOffset.UTC);
            LocalDate from = to.minusDays(daysBack);

            Map<String, LocalDate> lastLoginByUser = fetchLastLoginByDate(from, to);

            allUsers.removeIf(u -> {
                if (u == null || u.getId() == null || u.getId().isBlank()) return true;

                LocalDate last = lastLoginByUser.get(u.getId());
                return (last == null);
            });

            int after = allUsers.size();
            int filteredOut = before - after;

            log.info("Users total={} daysBack={} included={} filteredOut={}",
                    before, daysBack, after, filteredOut);

        } catch (Exception e) {
            log.warn("Failed to filter by audit activity. Returning unfiltered users. Cause={}", e.getMessage(), e);
        }

        log.debug("Finished fetching users from Qlik. Total after filter: {}", allUsers.size());
        return allUsers;
    }

    private QlikUserListResponse fetchUsersPage(String url) {
        URI rel = URI.create(url);

        String json = getJson(
                b -> b.replacePath(rel.getPath())
                        .replaceQuery(rel.getQuery())
                        .build(),
                "USERS",
                url
        );

        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);
            QlikUserListResponse wrapper = new QlikUserListResponse();

            if (root.has("data") && root.get("data").isArray()) {
                wrapper.setData(objectMapper.convertValue(root.get("data"), new TypeReference<List<QlikUserDto>>() {}));
                if (root.has("links")) {
                    wrapper.setLinks(objectMapper.convertValue(root.get("links"), QlikUserListResponse.Links.class));
                }
            } else {
                ObjectNode userNode = root.deepCopy();
                userNode.remove("links");
                QlikUserDto user = objectMapper.treeToValue(userNode, QlikUserDto.class);
                wrapper.setData(List.of(user));
                wrapper.setLinks(null);
            }

            return wrapper;
        } catch (Exception e) {
            log.warn("Failed to parse USERS response from {}. Cause={}", url, e.getMessage(), e);
            return null;
        }
    }

    private String getJson(Function<UriBuilder, URI> uriFn, String tag, String logRef) {
        final String ref = (logRef == null) ? "" : logRef;

        try {
            return webClient.get()
                    .uri(uriFn)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .header("Accept", "application/json")
                    .exchangeToMono(response -> {
                        int code = response.statusCode().value();

                        if (code >= 200 && code < 300) {
                            return response.bodyToMono(String.class);
                        }

                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .doOnNext(body -> log.warn(
                                        "{} API returned {}{} body={}",
                                        tag,
                                        code,
                                        ref.isBlank() ? "" : " for url=" + ref,
                                        truncate(body)
                                ))
                                .then(Mono.empty());
                    })
                    .onErrorResume(e -> {
                        log.warn("{} request failed{} Cause={}",
                                tag,
                                ref.isBlank() ? "" : " for url=" + ref + ".",
                                e.toString(),
                                e);
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.warn("{} request failed{} Cause={}",
                    tag,
                    ref.isBlank() ? "" : " for url=" + ref + ".",
                    e.getMessage(),
                    e);
            return null;
        }
    }

    private Map<String, LocalDate> fetchLastLoginByDate(LocalDate fromDateInclusive, LocalDate toDateInclusive) {
        Objects.requireNonNull(fromDateInclusive, "fromDateInclusive");
        Objects.requireNonNull(toDateInclusive, "toDateInclusive");

        if (fromDateInclusive.isAfter(toDateInclusive)) {
            throw new IllegalArgumentException("fromDate must be <= toDate");
        }

        String eventTimeRange = buildEventTimeRange(fromDateInclusive, toDateInclusive);

        String nextUrl = properties.getAuditEndpoint()
                + "?eventType=" + SESSION_BEGIN
                + "&eventTime=" + eventTimeRange
                + "&limit=100"
                + "&sort=-eventTime";


        Map<String, LocalDate> lastLoginByUser = new HashMap<>();

        int pages = 0;
        int events = 0;

        while (nextUrl != null) {
            pages++;

            URI rel = URI.create(nextUrl);
            String json = getJson(
                    b -> b.replacePath(rel.getPath())
                            .replaceQuery(rel.getQuery())
                            .build(),
                    "AUDIT",
                    nextUrl
            );

            if (json == null) {
                log.warn("AUDIT bulk fetch failed at page {} (url={}). Returning partial map size={}",
                        pages, nextUrl, lastLoginByUser.size());
                break;
            }

            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode data = root.get("data");

                if (data != null && data.isArray()) {
                    for (JsonNode ev : data) {
                        String userId = ev.path("userId").asText(null);
                        String eventTimeRaw = ev.path("eventTime").asText(null);
                        if (userId == null || userId.isBlank() || eventTimeRaw == null || eventTimeRaw.isBlank()) continue;

                        LocalDate eventDate;
                        try {
                            eventDate = ZonedDateTime.parse(eventTimeRaw)
                                    .withZoneSameInstant(ZoneOffset.UTC)
                                    .toLocalDate();
                        } catch (Exception ignore) {
                            continue;
                        }

                        if (eventDate.isBefore(fromDateInclusive) || eventDate.isAfter(toDateInclusive)) {
                            continue;
                        }

                        lastLoginByUser.merge(userId, eventDate, (a, b) -> a.isAfter(b) ? a : b);
                        events++;
                    }
                }

                QlikAuditListResponse.Links links = root.has("links")
                        ? objectMapper.convertValue(root.get("links"), QlikAuditListResponse.Links.class)
                        : null;

                nextUrl = nextHrefOrNull(links);

                if (pages % 10 == 0) {
                    log.debug("AUDIT bulk progress: pages={} events={} distinctUsers={}",
                            pages, events, lastLoginByUser.size());
                }

            } catch (Exception e) {
                log.warn("Failed to parse AUDIT bulk response (page {} url={}). Cause={}",
                        pages, nextUrl, e.getMessage(), e);
                break;
            }
        }

        log.info(
                "Qlik audit done pages={} events={} users={} range={}..{}",
                pages, events, lastLoginByUser.size(), fromDateInclusive, toDateInclusive
        );


        return lastLoginByUser;
    }

    private static String buildEventTimeRange(LocalDate from, LocalDate to) {
        ZonedDateTime fromUtc = from.atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime toUtc = to.atTime(23, 59, 59).atZone(ZoneOffset.UTC);

        return EVENT_TIME_FMT.format(fromUtc) + "/" + EVENT_TIME_FMT.format(toUtc);
    }


    private static String nextHrefOrNull(QlikUserListResponse.Links links) {
        if (links == null || links.getNext() == null) return null;
        String href = links.getNext().getHref();
        return (href == null || href.isBlank()) ? null : href;
    }

    private static String nextHrefOrNull(QlikAuditListResponse.Links links) {
        if (links == null || links.getNext() == null) return null;
        String href = links.getNext().getHref();
        return (href == null || href.isBlank()) ? null : href;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= LOG_TRUNCATE) return s;
        return s.substring(0, LOG_TRUNCATE) + "...(truncated)";
    }
}
