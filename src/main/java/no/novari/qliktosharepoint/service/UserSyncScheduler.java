package no.novari.qliktosharepoint.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSyncScheduler {

    private final UserSyncService userSyncService;

    private final AtomicBoolean running = new AtomicBoolean(false);
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
            if (tryRun("FULL_400", userSyncService::syncFull400)) {
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
        long startNs = System.nanoTime();

        log.info("Starting sync tag={}", tag);

        try {
            fn.run();
            log.info("Finished sync tag={} elapsed={}", tag, formatElapsed(startNs));
            return true;
        } catch (Throwable t) {
            log.error("Sync FAILED tag={} elapsed={} type={} msg={}",
                    tag, formatElapsed(startNs), t.getClass().getSimpleName(), safeMsg(t));
            return false;
        } finally {
            running.set(false);
        }
    }

    public boolean tryWithLock(String tag, Runnable fn) {
        if (!running.compareAndSet(false, true)) {
            log.info("Lock busy - skipping tag={} lastTag={}", tag, lastTag.get());
            return false;
        }

        lastTag.set(tag);
        long startNs = System.nanoTime();

        log.info("Starting task tag={}", tag);

        try {
            fn.run();
            log.info("Finished task tag={} elapsed={}", tag, formatElapsed(startNs));
            return true;
        } catch (Throwable t) {
            log.error("Task FAILED tag={} elapsed={} type={} msg={}",
                    tag, formatElapsed(startNs), t.getClass().getSimpleName(), safeMsg(t));
            return false;
        } finally {
            running.set(false);
        }
    }

    private static String formatElapsed(long startNs) {
        long totalSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNs);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + "m " + seconds + "s";
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        if (m != null && !m.isBlank()) return m;
        Throwable c = t.getCause();
        if (c != null && c.getMessage() != null && !c.getMessage().isBlank()) return c.getMessage();
        return "-";
    }
}
