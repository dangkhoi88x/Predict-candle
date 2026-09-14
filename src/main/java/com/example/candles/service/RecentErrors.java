package com.example.candles.service;

import com.example.candles.dto.response.OpsSnapshot;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last few things that went wrong on this instance, for the ops pane.
 *
 * It exists because of the first evening on Render: every candle sync and every live round failed
 * for hours, and the log viewer showed the tail of each stack trace with the line naming the cause
 * scrolled out of reach. Nothing on the admin page said anything was wrong. An error tracker was
 * the other option; this is the one that needs no account and no network, and it answers the
 * question actually asked that night — "is something failing, and what".
 *
 * In memory and per instance, deliberately. It is not a log: a restart empties it, and that is
 * fine for a list whose job is "what is happening now". Consecutive repeats of the same failure
 * fold into one row with a count, because an exchange ban produces the same error on every poll
 * of every open tab, and fifty identical rows would push out the one different error worth seeing.
 */
@Component
public class RecentErrors {

    static final int CAPACITY = 50;
    static final int MAX_SUMMARY = 300;

    private final Clock clock;
    private final Deque<OpsSnapshot.RecentError> errors = new ArrayDeque<>();

    public RecentErrors(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param source  where it happened — {@code upstream}, {@code server}, {@code sync}
     * @param where   the request or job it happened in
     * @param summary what went wrong, one line; truncated
     */
    public synchronized void record(String source, String where, String summary) {
        String line = oneLine(summary);
        OpsSnapshot.RecentError latest = errors.peekFirst();
        if (latest != null && latest.source().equals(source) && latest.where().equals(where)
                && latest.summary().equals(line)) {
            errors.pollFirst();
            errors.addFirst(new OpsSnapshot.RecentError(source, where, line, latest.firstAt(),
                    clock.instant(), latest.count() + 1));
            return;
        }
        errors.addFirst(new OpsSnapshot.RecentError(source, where, line, clock.instant(), clock.instant(), 1));
        while (errors.size() > CAPACITY) {
            errors.pollLast();
        }
    }

    /** Newest first. */
    public synchronized List<OpsSnapshot.RecentError> snapshot() {
        return new ArrayList<>(errors);
    }

    private static String oneLine(String summary) {
        String line = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
        return line.length() <= MAX_SUMMARY ? line : line.substring(0, MAX_SUMMARY - 1) + "…";
    }
}
