package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.candles.dto.response.OpsSnapshot;
import com.example.candles.entity.AppError;
import com.example.candles.repository.AppErrorRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the ops pane lists, now that it is rows rather than a deque.
 *
 * The behaviour worth pinning is the same as before — repeats fold, a different failure in between
 * starts a new row — plus the two things the move to the database is for: the list outlives the
 * process, and recording can never turn one failure into another.
 *
 * Deliberately not {@code @Transactional}: the store writes in a transaction of its own
 * (REQUIRES_NEW), so a test transaction could not roll those rows back anyway. The table is cleared
 * before each test instead, which also gives the fold rule a known newest row to work against.
 */
@SpringBootTest
class RecentErrorsTest {

    private static final Instant T0 = Instant.parse("2026-09-14T10:00:00Z");

    @Autowired private RecentErrors errors;
    @Autowired private AppErrorStore store;
    @Autowired private AppErrorRepository rows;
    @Autowired private ErrorRetentionScheduler retention;
    @MockitoBean private Clock clock;

    @BeforeEach
    void freshTable() {
        when(clock.instant()).thenReturn(T0);
        rows.deleteAllInBatch();
    }

    private void at(Instant instant) {
        when(clock.instant()).thenReturn(instant);
    }

    @Test
    void theSameFailureAgainFoldsIntoOneRowWithACount() {
        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        at(T0.plusSeconds(4));
        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        at(T0.plusSeconds(8));
        errors.record("upstream", "GET /api/live/round", "HTTP 418");

        List<OpsSnapshot.RecentError> listed = errors.snapshot();
        assertThat(listed).singleElement().satisfies(row -> {
            assertThat(row.count()).isEqualTo(3);
            assertThat(row.firstAt()).isEqualTo(T0);
            assertThat(row.lastAt()).isEqualTo(T0.plusSeconds(8));
        });
    }

    @Test
    void aDifferentFailureInBetweenStartsANewRowNewestFirst() {
        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        at(T0.plusSeconds(1));
        errors.record("sync", "BTCUSDT", "java.net.UnknownHostException: api.binance.com");
        at(T0.plusSeconds(2));
        errors.record("upstream", "GET /api/live/round", "HTTP 418");

        assertThat(errors.snapshot()).extracting(OpsSnapshot.RecentError::source)
                .containsExactly("upstream", "sync", "upstream");
        assertThat(errors.snapshot()).allMatch(row -> row.count() == 1);
    }

    /**
     * The reason these rows exist at all: a deploy or Render's nightly restart used to empty the
     * list, so the one thing it could not say was what had failed while nobody was watching.
     */
    @Test
    void theListOutlivesTheObjectThatRecordedIt() {
        errors.record("sync", "ETHUSDT", "Read timed out");

        RecentErrors afterRestart = new RecentErrors(store);

        assertThat(afterRestart.snapshot()).singleElement()
                .satisfies(row -> assertThat(row.where()).isEqualTo("ETHUSDT"));
    }

    /** A ban that ran all night and the same ban next week are two episodes, not one long row. */
    @Test
    void aRepeatLongAfterTheFoldWindowStartsItsOwnEpisode() {
        errors.record("upstream", "GET /api/live/round", "HTTP 418");
        at(T0.plus(Duration.ofHours(6)));
        errors.record("upstream", "GET /api/live/round", "HTTP 418");

        assertThat(errors.snapshot()).hasSize(2).allMatch(row -> row.count() == 1);
    }

    @Test
    void aStackTraceBecomesOneTruncatedLineAndALongPathIsCutToFitItsColumn() {
        errors.record("server", "GET /" + "x".repeat(400), "line one\n\tline two " + "y".repeat(500));

        OpsSnapshot.RecentError row = errors.snapshot().getFirst();
        assertThat(row.summary()).startsWith("line one line two ").hasSize(RecentErrors.MAX_SUMMARY);
        assertThat(row.where()).hasSize(RecentErrors.MAX_WHERE).endsWith("…");
    }

    /**
     * Recording happens where something has already gone wrong. A database that is itself the
     * problem must not replace the original failure with a different one.
     */
    @Test
    void aFailureWhileRecordingIsSwallowed() {
        AppErrorStore broken = mock(AppErrorStore.class);
        doThrow(new IllegalStateException("no connection")).when(broken).record(anyString(), anyString(), anyString());
        when(broken.recent(org.mockito.ArgumentMatchers.anyInt())).thenThrow(new IllegalStateException("no connection"));
        RecentErrors overABrokenStore = new RecentErrors(broken);

        assertThatCode(() -> overABrokenStore.record("server", "GET /x", "boom")).doesNotThrowAnyException();
        assertThat(overABrokenStore.snapshot()).isEmpty();
    }

    /**
     * Both limits are needed and bound different failures: age keeps the table a picture of the
     * last fortnight, and the row cap stops one bad night filling it inside that window.
     */
    @Test
    void retentionDropsWhatIsOldAndCapsWhatIsLeft() {
        rows.save(new AppError("sync", "old", "gone", T0.minus(Duration.ofDays(30))));
        for (int i = 0; i < 5; i++) {
            rows.save(new AppError("server", "recent-" + i, "kept", T0.minusSeconds(60L - i)));
        }
        rows.flush();

        int removed = retention.purge(Duration.ofDays(14), 2);

        assertThat(removed).isEqualTo(4);   // one by age, three over the cap
        assertThat(rows.findAll()).extracting(AppError::getWhereAt)
                .containsExactlyInAnyOrder("recent-4", "recent-3");
    }
}
