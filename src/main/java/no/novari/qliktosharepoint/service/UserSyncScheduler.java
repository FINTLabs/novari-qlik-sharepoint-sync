package no.novari.qliktosharepoint.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.novari.qliktosharepoint.cache.EntraCacheRefresher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncScheduler {

    private final UserSyncService userSyncService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final EntraCacheRefresher entraCacheRefresher;

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT5M")
    public void scheduledRun() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous sync still running - skipping this run");
            return;
        }
        try {
            entraCacheRefresher.refreshCache();
            userSyncService.runSyncOnce();
        } finally {
            running.set(false);
        }
    }
}
