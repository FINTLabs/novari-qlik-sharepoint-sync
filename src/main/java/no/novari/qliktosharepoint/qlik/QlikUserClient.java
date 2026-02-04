package no.novari.qliktosharepoint.qlik;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.config.QlikProperties;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

@Slf4j
@Component
public class QlikUserClient {

    private static final int LOG_TRUNCATE = 800;
    private static final int RECENT_DAYS = 90;

    private static final DateTimeFormatter EVENT_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ARCHIVE_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final int ARCHIVE_MAX_DAYS_BETWEEN_PROGRESS_LOG = 20;

    private final ExecutorService archivePool = Executors.newFixedThreadPool(32);

    private final WebClient webClient;
    private final QlikProperties properties;
    private final ObjectMapper objectMapper;

    private final java.net.http.HttpClient jdkHttpClient;
    private final JsonFactory jsonFactory = new JsonFactory();

    private final ConcurrentHashMap<String, Instant> activeWithin400 = new ConcurrentHashMap<>();

    private volatile Instant lastRecentRefreshAtUtc;
    private volatile Instant lastFullRefreshAtUtc;

    public QlikUserClient(QlikProperties properties,
                          WebClient.Builder webClientBuilder,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        int mb = Math.max(1, properties.getMaxInMemorySize());
        int maxBytes = mb * 1024 * 1024;

        var strategies = org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                .build();

        HttpClient reactorHttp = HttpClient.create()
                .compress(true)
                .responseTimeout(Duration.ofSeconds(120));

        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(reactorHttp))
                .exchangeStrategies(strategies)
                .defaultHeader("Accept-Encoding", "gzip")
                .build();

        this.jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        log.info("Qlik clients initialized: webClient(maxInMemorySize={}MB), jdkHttpClient(archive streaming)", mb);
    }

    public List<QlikUserDto> getAllUsersRecent90UsingCache400() {
        List<QlikUserDto> allUsers = fetchAllUsers();
        if (allUsers == null) return null;

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(RECENT_DAYS - 1);

        AuditResult recent = fetchRecentBulk(from, to);

        for (var e : recent.lastByUser.entrySet()) {
            activeWithin400.merge(e.getKey(), e.getValue(), (a, b) -> a.isAfter(b) ? a : b);
        }
        lastRecentRefreshAtUtc = Instant.now();

        if (!recent.complete) {
            log.warn("AUDIT_RECENT incomplete -> returning UNFILTERED users. recentUsersFound={} cacheSize={}",
                    recent.lastByUser.size(), activeWithin400.size());
            return allUsers;
        }

        return filterUsersByCache(allUsers, "RECENT_90");
    }

    public List<QlikUserDto> refreshCacheFull400AndGetAllUsers() {
        List<QlikUserDto> allUsers = fetchAllUsers();
        if (allUsers == null) return null;

        int daysBack = Math.max(properties.getAuditDaysBack(), RECENT_DAYS);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate fullFrom = today.minusDays(daysBack);
        LocalDate recentFrom = today.minusDays(RECENT_DAYS - 1);

        AuditResult recent = fetchRecentBulk(recentFrom, today);
        for (var e : recent.lastByUser.entrySet()) {
            activeWithin400.merge(e.getKey(), e.getValue(), (a, b) -> a.isAfter(b) ? a : b);
        }
        lastRecentRefreshAtUtc = Instant.now();

        boolean complete = recent.complete;

        Set<String> userIds = extractUserIds(allUsers);
        Set<String> missing = new HashSet<>(userIds);
        missing.removeAll(activeWithin400.keySet());
        int missingBefore = missing.size();

        LocalDate archiveTo = recentFrom.minusDays(1);
        if (!missing.isEmpty() && !fullFrom.isAfter(archiveTo)) {
            boolean archiveOk = fetchArchiveForMissingStreaming(fullFrom, archiveTo, missing);
            complete = complete && archiveOk;
        }

        Instant cutoff = fullFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        int beforeTrim = activeWithin400.size();
        activeWithin400.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBefore(cutoff));
        int afterTrim = activeWithin400.size();

        lastFullRefreshAtUtc = Instant.now();

        log.info("AUDIT_FULL done complete={} daysBack={} recentRange={}..{} missingBeforeArchive={} cacheBeforeTrim={} cacheAfterTrim={}",
                complete, daysBack, recentFrom, today, missingBefore, beforeTrim, afterTrim);

        if (!complete) {
            log.warn("AUDIT_FULL incomplete -> returning UNFILTERED users to avoid false negatives. cacheSize={}", activeWithin400.size());
            return allUsers;
        }

        return filterUsersByCache(allUsers, "FULL_" + daysBack);
    }

    private List<QlikUserDto> fetchAllUsers() {
        List<QlikUserDto> allUsers = new ArrayList<>();
        String nextUrl = properties.getUsersEndpoint() + "?limit=" + properties.getApiPageSize();

        while (nextUrl != null) {
            QlikUserListResponse page = fetchUsersPage(nextUrl);
            if (page == null) {
                log.warn("Qlik USERS fetch failed - returning null");
                return null;
            }

            if (page.getData() != null && !page.getData().isEmpty()) {
                allUsers.addAll(page.getData());
            } else {
                log.warn("No users in response from Qlik for URL {}", nextUrl);
            }

            nextUrl = nextHrefOrNull(page.getLinks());
        }

        return allUsers;
    }

    private QlikUserListResponse fetchUsersPage(String url) {
        URI rel = URI.create(url);

        String json = getJsonBlocking(
                b -> b.replacePath(rel.getPath()).replaceQuery(rel.getQuery()).build(),
                "USERS",
                url
        );

        if (json == null) return null;

        try {
            JsonNode root = objectMapper.readTree(json);
            QlikUserListResponse wrapper = new QlikUserListResponse();

            if (root.has("data") && root.get("data").isArray()) {
                wrapper.setData(objectMapper.convertValue(root.get("data"), new TypeReference<>() {}));
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

    private Set<String> extractUserIds(List<QlikUserDto> users) {
        Set<String> out = new HashSet<>();
        for (QlikUserDto u : users) {
            if (u != null && u.getId() != null && !u.getId().isBlank()) out.add(u.getId());
        }
        return out;
    }

    private List<QlikUserDto> filterUsersByCache(List<QlikUserDto> allUsers, String modeTag) {
        int before = allUsers.size();
        int blank = 0;

        Iterator<QlikUserDto> it = allUsers.iterator();
        while (it.hasNext()) {
            QlikUserDto u = it.next();
            if (u == null || u.getId() == null || u.getId().isBlank()) {
                blank++;
                it.remove();
                continue;
            }
            if (!activeWithin400.containsKey(u.getId())) {
                it.remove();
            }
        }

        int after = allUsers.size();

        log.info("Users filtered mode={} qlikUsersTotal={} blankId={} included={} filteredOut={} cacheSize={} lastRecent={} lastFull={}",
                modeTag, before, blank, after, (before - after), activeWithin400.size(),
                lastRecentRefreshAtUtc, lastFullRefreshAtUtc);

        return allUsers;
    }

    private static class AuditResult {
        final boolean complete;
        final Map<String, Instant> lastByUser;

        AuditResult(boolean complete, Map<String, Instant> lastByUser) {
            this.complete = complete;
            this.lastByUser = lastByUser;
        }
    }

    private AuditResult fetchRecentBulk(LocalDate fromInclusive, LocalDate toInclusive) {
        String eventTimeRange = buildEventTimeRange(fromInclusive, toInclusive);

        String nextUrl = properties.getAuditEndpoint()
                + "?eventType=" + safeEncode(properties.getAuditEventType())
                + "&eventTime=" + safeEncode(eventTimeRange)
                + "&limit=100"
                + "&sort=-eventTime";

        Map<String, Instant> lastByUser = new HashMap<>();
        int pages = 0;
        int eventsScanned = 0;
        boolean complete = true;

        while (nextUrl != null) {
            pages++;
            String currentUrl = nextUrl;

            URI rel = URI.create(currentUrl);
            String json = getJsonBlocking(
                    b -> b.replacePath(rel.getPath()).replaceQuery(rel.getQuery()).build(),
                    "AUDIT_RECENT",
                    currentUrl
            );

            if (json == null) {
                complete = false;
                log.warn("AUDIT_RECENT failed page={} url={}. Returning partial usersFound={}",
                        pages, currentUrl, lastByUser.size());
                break;
            }

            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode data = root.get("data");

                if (data != null && data.isArray()) {
                    for (JsonNode ev : data) {
                        eventsScanned++;

                        String userId = ev.path("userId").asText(null);
                        String eventTimeRaw = ev.path("eventTime").asText(null);
                        if (userId == null || userId.isBlank() || eventTimeRaw == null || eventTimeRaw.isBlank()) continue;

                        Instant t;
                        try {
                            t = OffsetDateTime.parse(eventTimeRaw).toInstant();
                        } catch (Exception ignore) {
                            continue;
                        }

                        lastByUser.putIfAbsent(userId, t);
                    }
                }

                QlikAuditListResponse.Links links = root.has("links")
                        ? objectMapper.convertValue(root.get("links"), QlikAuditListResponse.Links.class)
                        : null;

                nextUrl = nextHrefOrNull(links);

            } catch (Exception e) {
                complete = false;
                log.warn("Failed to parse AUDIT_RECENT page={} url={}. Cause={}", pages, currentUrl, e.getMessage(), e);
                break;
            }
        }

        log.info("Qlik audit recent done complete={} pages={} eventsScanned={} usersFound={} range={}..{}",
                complete, pages, eventsScanned, lastByUser.size(), fromInclusive, toInclusive);

        return new AuditResult(complete, lastByUser);
    }

    private boolean fetchArchiveForMissingStreaming(LocalDate fromInclusive,
                                                    LocalDate toInclusive,
                                                    Set<String> missingUserIds) {
        if (missingUserIds.isEmpty()) return true;

        String archivePath = properties.getAuditEndpoint() + "/archive";
        String wantedEventType = properties.getAuditEventType();

        int parallelism = archiveParallelism();

        Set<String> missing = ConcurrentHashMap.newKeySet();
        missing.addAll(missingUserIds);

        int daysTried = 0;
        int daysFetched = 0;
        long eventsScanned = 0;
        int done = 0;

        CompletionService<DayScanResult> cs = new ExecutorCompletionService<>(archivePool);
        int inFlight = 0;

        LocalDate d = toInclusive;

        while (!d.isBefore(fromInclusive) || inFlight > 0) {

            while (!d.isBefore(fromInclusive) && inFlight < parallelism && !missing.isEmpty()) {
                LocalDate day = d;
                d = d.minusDays(1);
                daysTried++;

                cs.submit(() -> {
                    if (missing.isEmpty()) return DayScanResult.okNoFetch();

                    String dateParam = ARCHIVE_DATE_FMT.format(day);
                    URI uri = buildAbsoluteUri(archivePath + "?date=" + dateParam);

                    HttpRequest req = HttpRequest.newBuilder(uri)
                            .GET()
                            .timeout(Duration.ofSeconds(180))
                            .header("Authorization", "Bearer " + properties.getApiToken())
                            .header("Accept", "application/json")
                            .build();

                    HttpResponse<InputStream> resp;
                    try {
                        resp = sendWithRetry(req, "AUDIT_ARCHIVE", "date=" + dateParam);
                    } catch (Exception ex) {
                        return DayScanResult.fail("request_failed date=" + dateParam + " cause=" + ex);
                    }

                    int code = resp.statusCode();
                    if (code < 200 || code >= 300) {
                        closeQuietly(resp.body());
                        return DayScanResult.fail("non_2xx date=" + dateParam + " status=" + code);
                    }

                    long scannedThisDay = 0;
                    Map<String, Instant> dayMax = new HashMap<>();

                    InputStream raw = resp.body();
                    String enc = resp.headers().firstValue("Content-Encoding").orElse("");
                    InputStream decoded = "gzip".equalsIgnoreCase(enc)
                            ? new java.util.zip.GZIPInputStream(raw, 1 << 20)
                            : raw;

                    try (InputStream in = new java.io.BufferedInputStream(decoded, 1 << 20);
                         JsonParser p = jsonFactory.createParser(in)) {
                        if (!moveParserToEventsArray(p)) {
                            return DayScanResult.okFetched(0, 0);
                        }

                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            if (missing.isEmpty()) break;

                            if (p.currentToken() != JsonToken.START_OBJECT) {
                                p.skipChildren();
                                continue;
                            }

                            String userId = null;
                            String eventType = null;
                            String eventTimeRaw = null;

                            while (p.nextToken() != JsonToken.END_OBJECT) {
                                String field = p.currentName();
                                if (field == null) {
                                    p.nextToken();
                                    p.skipChildren();
                                    continue;
                                }
                                p.nextToken();

                                switch (field) {
                                    case "userId" -> userId = p.getValueAsString();
                                    case "eventType" -> eventType = p.getValueAsString();
                                    case "eventTime" -> eventTimeRaw = p.getValueAsString();
                                    default -> p.skipChildren();
                                }
                            }

                            scannedThisDay++;

                            if (userId == null || userId.isBlank()) continue;
                            if (!missing.contains(userId)) continue;
                            if (eventType == null || !eventType.equals(wantedEventType)) continue;
                            if (eventTimeRaw == null || eventTimeRaw.isBlank()) continue;

                            Instant t;
                            try {
                                t = OffsetDateTime.parse(eventTimeRaw).toInstant();
                            } catch (Exception ignore) {
                                continue;
                            }

                            dayMax.merge(userId, t, (a, b) -> a.isAfter(b) ? a : b);
                        }
                    } catch (Exception e) {
                        return DayScanResult.fail("parse_failed date=" + dateParam + " cause=" + e);
                    }

                    int found = 0;
                    for (var e : dayMax.entrySet()) {
                        activeWithin400.merge(e.getKey(), e.getValue(), (a, b) -> a.isAfter(b) ? a : b);
                        if (missing.remove(e.getKey())) found++;
                    }

                    return DayScanResult.okFetched(scannedThisDay, found);
                });

                inFlight++;
            }

            if (inFlight == 0) break;

            DayScanResult r;
            try {
                r = cs.take().get();
            } catch (Exception e) {
                log.warn("AUDIT_ARCHIVE worker failed cause={}", e.toString(), e);
                missingUserIds.clear();
                missingUserIds.addAll(missing);
                return false;
            }

            inFlight--;
            done++;

            if (!r.ok) {
                log.warn("AUDIT_ARCHIVE failed {} daysFetched={} daysTried={} missingRemaining={} threads={}",
                        r.err, daysFetched, daysTried, missing.size(), parallelism);
                missingUserIds.clear();
                missingUserIds.addAll(missing);
                return false;
            }

            if (r.fetched) daysFetched++;
            eventsScanned += r.eventsScanned;

            if (done % ARCHIVE_MAX_DAYS_BETWEEN_PROGRESS_LOG == 0) {
                log.info("AUDIT_ARCHIVE progress done={} daysFetched={} daysTried={} cacheSize={} missingRemaining={} threads={}",
                        done, daysFetched, daysTried, activeWithin400.size(), missing.size(), parallelism);
            }

            if (missing.isEmpty()) {
                while (inFlight > 0) {
                    try { cs.take().get(); } catch (Exception ignore) {}
                    inFlight--;
                }

                log.info("AUDIT_ARCHIVE early-stop: all missing users found. done={} daysFetched={} daysTried={} eventsScanned={} threads={}",
                        done, daysFetched, daysTried, eventsScanned, parallelism);

                missingUserIds.clear();
                return true;
            }
        }

        missingUserIds.clear();
        missingUserIds.addAll(missing);

        log.info("AUDIT_ARCHIVE done done={} daysFetched={} daysTried={} cacheSize={} missingRemaining={} eventsScanned={} threads={}",
                done, daysFetched, daysTried, activeWithin400.size(), missing.size(), eventsScanned, parallelism);

        return true;
    }

    private static final class DayScanResult {
        final boolean ok;
        final boolean fetched;
        final long eventsScanned;
        final int usersFound;
        final String err;

        private DayScanResult(boolean ok, boolean fetched, long eventsScanned, int usersFound, String err) {
            this.ok = ok;
            this.fetched = fetched;
            this.eventsScanned = eventsScanned;
            this.usersFound = usersFound;
            this.err = err;
        }

        static DayScanResult okNoFetch() { return new DayScanResult(true, false, 0, 0, null); }
        static DayScanResult okFetched(long eventsScanned, int usersFound) { return new DayScanResult(true, true, eventsScanned, usersFound, null); }
        static DayScanResult fail(String err) { return new DayScanResult(false, false, 0, 0, err); }
    }

    private int archiveParallelism() {
        String v = System.getProperty("qlik.audit.archive.threads", "4");
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (Exception ignore) {
            n = 4;
        }
        if (n != 4 && n != 8) n = 4;
        return n;
    }

    private boolean moveParserToEventsArray(JsonParser p) throws Exception {
        JsonToken t = p.nextToken();
        if (t == null) return false;

        if (t == JsonToken.START_ARRAY) {
            return true;
        }

        if (t != JsonToken.START_OBJECT) {
            p.skipChildren();
            return false;
        }

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            if (field == null) {
                p.nextToken();
                p.skipChildren();
                continue;
            }
            p.nextToken();
            if ("data".equals(field) && p.currentToken() == JsonToken.START_ARRAY) {
                return true;
            }
            p.skipChildren();
        }

        return false;
    }

    private HttpResponse<InputStream> sendWithRetry(HttpRequest req, String tag, String ref) throws Exception {
        final int maxAttempts = 6;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpResponse<InputStream> resp = jdkHttpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();

            if (code >= 200 && code < 300) {
                return resp;
            }

            long retryAfterMs = parseRetryAfterMs(resp.headers().firstValue("Retry-After").orElse(null));
            boolean retryable = (code == 429) || (code >= 500 && code <= 599);

            if (!retryable || attempt == maxAttempts) {
                log.warn("{} failed ref={} status={} attempt={}/{} retryAfterMs={}",
                        tag, ref, code, attempt, maxAttempts, retryAfterMs);
                return resp;
            }

            long sleepMs = retryAfterMs > 0 ? retryAfterMs : backoffMs(attempt);
            log.warn("{} retrying ref={} status={} attempt={}/{} sleepMs={}",
                    tag, ref, code, attempt, maxAttempts, sleepMs);

            closeQuietly(resp.body());
            sleepQuietly(sleepMs);
        }

        throw new IllegalStateException("unreachable");
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (Exception ignore) {}
    }

    private static long parseRetryAfterMs(String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isBlank()) return 0;
        try {
            long seconds = Long.parseLong(retryAfterHeader.trim());
            return TimeUnit.SECONDS.toMillis(Math.max(0, seconds));
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static long backoffMs(int attempt) {
        long exp = 500L * (1L << Math.min(6, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, 350);
        return Math.min(30_000, exp + jitter);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String getJsonBlocking(Function<UriBuilder, URI> uriFn, String tag, String logRef) {
        try {
            return webClient.get()
                    .uri(uriFn)
                    .header("Authorization", "Bearer " + properties.getApiToken())
                    .header("Accept", "application/json")
                    .exchangeToMono(resp -> {
                        int code = resp.statusCode().value();
                        if (code >= 200 && code < 300) return resp.bodyToMono(String.class);

                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .doOnNext(body -> log.warn("{} API returned {} ref={} body={}",
                                        tag, code, logRef, truncate(body)))
                                .then(Mono.empty());
                    })
                    .block();
        } catch (Exception e) {
            log.warn("{} request failed ref={} Cause={}", tag, logRef, e.toString(), e);
            return null;
        }
    }

    private URI buildAbsoluteUri(String relativePathAndQuery) {
        String base = properties.getBaseUrl();
        if (base.endsWith("/") && relativePathAndQuery.startsWith("/")) {
            return URI.create(base.substring(0, base.length() - 1) + relativePathAndQuery);
        }
        if (!base.endsWith("/") && !relativePathAndQuery.startsWith("/")) {
            return URI.create(base + "/" + relativePathAndQuery);
        }
        return URI.create(base + relativePathAndQuery);
    }

    private static String buildEventTimeRange(LocalDate from, LocalDate to) {
        ZonedDateTime fromUtc = from.atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime toUtc = to.atTime(23, 59, 59).atZone(ZoneOffset.UTC);
        return EVENT_TIME_FMT.format(fromUtc) + "/" + EVENT_TIME_FMT.format(toUtc);
    }

    private static String safeEncode(String s) {
        if (s == null) return "";
        return s.replace(":", "%3A")
                .replace("/", "%2F")
                .replace("+", "%2B")
                .replace(" ", "%20");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        if (s.length() <= LOG_TRUNCATE) return s;
        return s.substring(0, LOG_TRUNCATE) + "...(truncated)";
    }
}
