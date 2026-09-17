package com.example.candles.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.candles.entity.AppError;
import com.example.candles.repository.AppErrorRepository;

/**
 * The rows behind {@link RecentErrors} — a bean of its own for one reason that is easy to get
 * wrong: {@code @Transactional} is a proxy, and a proxy is not involved when a class calls its own
 * method. Writing this as a private method of {@code RecentErrors} would compile, read correctly,
 * and quietly join the caller's transaction — the one being rolled back, which is where the row
 * would go with it.
 *
 * {@code REQUIRES_NEW} for the same reason: {@code GlobalExceptionHandler} records while the
 * request's transaction is already doomed.
 */
@Service
public class AppErrorStore {

    private final AppErrorRepository errors;
    private final Clock clock;
    private final Duration foldWindow;

    public AppErrorStore(AppErrorRepository errors, Clock clock,
                         @Value("${candles.errors.fold-window:PT1H}") Duration foldWindow) {
        this.errors = errors;
        this.clock = clock;
        this.foldWindow = foldWindow;
    }

    /**
     * Folds into the newest row when it is the same failure and recent enough, else starts a new
     * one. Only the newest row folds: a different failure in between starts a new row, so the list
     * reads as a sequence of episodes rather than a set of counters.
     *
     * @return true when this started a new episode rather than folding into the one on top. That
     *         is the moment worth telling somebody about — a repeat of a failure already on the
     *         pane is not news, and an exchange ban would otherwise send a message a second.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(String source, String where, String summary) {
        Instant now = clock.instant();
        Optional<AppError> newest = errors.findFirstByOrderByLastAtDescIdDesc();
        if (newest.isPresent() && matches(newest.get(), source, where, summary)
                && !newest.get().getLastAt().isBefore(now.minus(foldWindow))) {
            /* Folded, but not forever: a ban that ran all night and the same ban a week later are
               two episodes, and one row spanning both would say neither happened when it did. */
            AppError row = newest.get();
            row.repeated(now);
            errors.save(row);
            return false;
        }
        errors.save(new AppError(source, where, summary, now));
        return true;
    }

    @Transactional(readOnly = true)
    public List<AppError> recent(int limit) {
        return errors.findByOrderByLastAtDescIdDesc(PageRequest.of(0, limit));
    }

    private static boolean matches(AppError row, String source, String where, String summary) {
        return row.getSource().equals(source) && row.getWhereAt().equals(where)
                && row.getSummary().equals(summary);
    }
}
