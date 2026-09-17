package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

import com.example.candles.dto.response.OpsSnapshot;

/**
 * The last few things that went wrong, for the ops pane.
 *
 * It exists because of the first evening on Render: every candle sync and every live round failed
 * for hours, and the log viewer showed the tail of each stack trace with the line naming the cause
 * scrolled out of reach. Nothing on the admin page said anything was wrong.
 *
 * <b>It used to be a deque in memory, and the restart is what changed that.</b> A list emptied by
 * every deploy — and by every night Render restarts a free instance — could answer "is something
 * failing right now" and nothing at all about what happened while nobody was looking, which is the
 * question that evening actually asked. The rows live in {@code app_errors} now
 * ({@link AppErrorStore}), and {@link ErrorRetentionScheduler} keeps that table bounded.
 *
 * A failure that starts its own row is also sent to {@link ErrorAlertService}, which is the half
 * that reaches somebody rather than waiting to be opened.
 *
 * <b>Recording never throws and never fails a request.</b> This is called from a servlet filter, an
 * exception handler and a scheduler — all places where something has already gone wrong — so a
 * database that is itself the problem must not turn one failure into a different one. A failed
 * write goes to the log and no further; a failed read leaves the pane's other figures intact.
 */
@Component
public class RecentErrors {

    private static final Logger log = LoggerFactory.getLogger(RecentErrors.class);

    static final int CAPACITY = 50;
    static final int MAX_SUMMARY = 300;
    static final int MAX_WHERE = 200;
    static final int MAX_SOURCE = 32;

    private final AppErrorStore store;
    private final ErrorAlertService alerts;

    public RecentErrors(AppErrorStore store, ErrorAlertService alerts) {
        this.store = store;
        this.alerts = alerts;
    }

    /**
     * @param source  where it happened — {@code upstream}, {@code server}, {@code sync}, {@code telegram}
     * @param where   the request or job it happened in
     * @param summary what went wrong, one line; truncated to fit its column
     */
    public void record(String source, String where, String summary) {
        String trimmedSource = cut(source, MAX_SOURCE);
        String trimmedWhere = cut(where, MAX_WHERE);
        String line = oneLine(summary);
        try {
            // Only a new episode is news: a repeat folds into the row on top, and an exchange ban
            // repeating on every poll would otherwise be a message a second.
            if (store.record(trimmedSource, trimmedWhere, line)) {
                alerts.newFailure(trimmedSource, trimmedWhere, line);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record an error for the ops pane: {}", e.toString());
        }
    }

    /** Newest first. */
    public List<OpsSnapshot.RecentError> snapshot() {
        try {
            return store.recent(CAPACITY).stream()
                    .map(row -> new OpsSnapshot.RecentError(row.getSource(), row.getWhereAt(),
                            row.getSummary(), row.getFirstAt(), row.getLastAt(), row.getCount()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Could not read recent errors: {}", e.toString());
            return List.of();
        }
    }

    private static String oneLine(String summary) {
        String line = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
        return cut(line, MAX_SUMMARY);
    }

    /** Truncation is this side's job: the columns are sized, and a stack trace is not. */
    private static String cut(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
