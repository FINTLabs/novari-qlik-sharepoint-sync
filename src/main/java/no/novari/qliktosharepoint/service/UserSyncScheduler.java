package no.novari.qliktosharepoint.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncScheduler {

    private final UserSyncService userSyncService;
    public AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean fullPending = new AtomicBoolean(true);
    private final AtomicReference<String> lastTag = new AtomicReference<>("NONE");

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("Scheduler ready: will run FULL_400 as soon as lock is available");
        fullPending.set(true);
    }


    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5M")
    public void scheduled5Min() {
        if (fullPending.get()) {
            boolean ranFull = tryRun("FULL_400", userSyncService::syncFull400);
            if (ranFull) {
                fullPending.set(false);
            }
            return;
        }

        tryRun("RECENT_90", userSyncService::syncRecent90);
    }

    @Scheduled(cron = "0 30 1 * * *", zone = "Europe/Oslo")
    public void scheduledDailyFull() {
        log.info("Daily FULL_400 requested -> will run when lock is available");
        fullPending.set(true);
    }

    private boolean tryRun(String tag, Runnable fn) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous sync still running - skipping run tag={} lastTag={}", tag, lastTag.get());
            return false;
        }

        lastTag.set(tag);

        long start = System.nanoTime();
        log.info("Starting sync tag={}", tag);

        try {
            fn.run();
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("Finished sync tag={} elapsedMs={}", tag, ms);
            return true;
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.error("Sync FAILED tag={} elapsedMs={} cause={}", tag, ms, e.toString(), e);
            return false;
        } finally {
            running.set(false);
        }
    }
}
