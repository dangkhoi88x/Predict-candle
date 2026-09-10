package com.example.candles.service;

import com.example.candles.config.CandlesProperties;
import com.example.candles.dto.response.AdminPlayerDetail;
import com.example.candles.dto.response.AdminPlayerPage;
import com.example.candles.dto.response.PlayerSummary;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The player list as a page rather than the whole table, and one account read in detail.
 *
 * Every assertion here is scoped by a search on a unique tag rather than taken off the top of
 * the list. A developer database holds whatever the app was last played on, so "the busiest two
 * accounts" is not a fact a test can assert about — but "the busiest two of the four I just
 * created" is, and it exercises the filter at the same time.
 */
@SpringBootTest
@Transactional
class AdminPlayerDetailTest {

    @Autowired private AdminPlayerService service;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private LivePredictionRepository livePredictions;
    @Autowired private CandlesProperties properties;

    /** Unique per test method, so a search on it sees this test's accounts and nothing else. */
    private final String tag = "tag" + UUID.randomUUID().toString().substring(0, 8);

    private User player(String suffix) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), tag + "-" + suffix);
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    private Asset asset() {
        return assets.saveAndFlush(new Asset(
                "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Test pair", AssetType.CRYPTO));
    }

    private void guesses(User user, Asset asset, GuessMode mode, int count, int startIndex) {
        for (int i = 0; i < count; i++) {
            guessResults.save(new GuessResult(user, asset, "1h", startIndex + i, 1,
                    Direction.LONG, Direction.LONG, mode));
        }
        guessResults.flush();
    }

    @Test
    void theListComesBackOnePageAtATimeBusiestFirst() {
        Asset asset = asset();
        User quiet = player("quiet");
        User busy = player("busy");
        User middling = player("middling");
        guesses(busy, asset, GuessMode.PRACTICE, 5, 100);
        guesses(middling, asset, GuessMode.PRACTICE, 3, 200);
        guesses(quiet, asset, GuessMode.PRACTICE, 1, 300);

        AdminPlayerPage first = service.players(tag, null, 0, 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.players()).extracting(PlayerSummary::displayName)
                .containsExactly(busy.getDisplayName(), middling.getDisplayName());
        assertThat(first.players().getFirst().guesses()).isEqualTo(5);
        assertThat(first.hasMore()).isTrue();

        AdminPlayerPage second = service.players(tag, null, 1, 2);
        assertThat(second.players()).extracting(PlayerSummary::displayName)
                .containsExactly(quiet.getDisplayName());
        // The last page says so, which is what stops the pager offering a page that is not there.
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void searchMatchesTheAddressAsWellAsTheNameAndTheTotalFollowsIt() {
        User user = player("searchable");

        assertThat(service.players(tag, null, 0, 50).total()).isEqualTo(1);

        // Pasting an address in is the other half of what this box is for.
        AdminPlayerPage byAddress = service.players(user.getWalletAddress().toUpperCase(), null, 0, 50);
        assertThat(byAddress.players()).extracting(PlayerSummary::id).containsExactly(user.getId());

        AdminPlayerPage nothing = service.players(tag + "-nobody", null, 0, 50);
        assertThat(nothing.players()).isEmpty();
        // The headcount means "matching what you typed", or it would contradict the rows below it.
        assertThat(nothing.total()).isZero();
        assertThat(nothing.hasMore()).isFalse();
    }

    @Test
    void theRecentSortIsADifferentOrderFromTheBusiestOne() {
        Asset asset = asset();
        User loud = player("loud");
        User lately = player("lately");
        guesses(loud, asset, GuessMode.PRACTICE, 4, 400);
        guesses(lately, asset, GuessMode.PRACTICE, 1, 500);   // fewer, but after

        assertThat(service.players(tag, "active", 0, 50).players())
                .extracting(PlayerSummary::id).containsExactly(loud.getId(), lately.getId());
        assertThat(service.players(tag, "recent", 0, 50).players())
                .extracting(PlayerSummary::id).containsExactly(lately.getId(), loud.getId());
    }

    @Test
    void theDetailSplitsAnAccountByGameAndByPair() {
        Asset first = asset();
        Asset second = asset();
        User user = player("detail");
        guesses(user, first, GuessMode.PRACTICE, 3, 600);
        guesses(user, first, GuessMode.DAILY, 2, 700);
        guesses(user, second, GuessMode.ARCHIVE, 1, 800);

        AdminPlayerDetail detail = service.detail(user.getId());

        assertThat(detail.account().guesses()).isEqualTo(6);
        assertThat(detail.modes()).extracting(AdminPlayerDetail.ModeTally::mode)
                .containsExactlyInAnyOrder("PRACTICE", "DAILY", "ARCHIVE");
        assertThat(detail.modes()).extracting(AdminPlayerDetail.ModeTally::guesses)
                .containsExactlyInAnyOrder(3L, 2L, 1L);
        assertThat(detail.assets()).extracting(AdminPlayerDetail.AssetTally::symbol)
                .containsExactlyInAnyOrder(first.getSymbol(), second.getSymbol());
        // Nothing was imported into this account, so there is no legacy block to draw.
        assertThat(detail.legacy()).isNull();
    }

    /**
     * A timed-out guess has no direction. It is in the totals — the rest of the app counts one
     * against the player — so the row has to be able to say "no answer" rather than pick a side.
     */
    @Test
    void aTimedOutGuessIsListedWithNoDirection() {
        Asset asset = asset();
        User user = player("timeout");
        guessResults.saveAndFlush(new GuessResult(user, asset, "1h", 900, 1,
                null, Direction.LONG, GuessMode.PRACTICE));

        AdminPlayerDetail detail = service.detail(user.getId());

        assertThat(detail.recentGuesses()).singleElement().satisfies(g -> {
            assertThat(g.guessedDirection()).isNull();
            assertThat(g.actualDirection()).isEqualTo("LONG");
            assertThat(g.correct()).isFalse();
        });
        assertThat(detail.account().guesses()).isEqualTo(1);
    }

    /**
     * Live accuracy has to be read against settled calls, not against every call: a round still
     * running is not one the player got wrong. The three figures are separate for that reason,
     * and a call with no candle carries a null verdict rather than a false one.
     */
    @Test
    void liveCallsCountSeparatelyFromSettledOnes() {
        Asset asset = asset();
        User user = player("live");
        Instant settledAt = Instant.parse("2024-03-02T09:00:00Z");
        Instant openAt = Instant.parse("2024-03-02T10:00:00Z");

        candles.saveAndFlush(new Candle(asset, properties.timeframe(), settledAt,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("99"),
                new BigDecimal("110"), BigDecimal.TEN));
        livePredictions.saveAll(List.of(
                new LivePrediction(user, asset, properties.timeframe(), settledAt, Direction.LONG),
                new LivePrediction(user, asset, properties.timeframe(), openAt, Direction.SHORT)));
        livePredictions.flush();

        AdminPlayerDetail detail = service.detail(user.getId());

        assertThat(detail.live().calls()).isEqualTo(2);
        assertThat(detail.live().settled()).isEqualTo(1);
        assertThat(detail.live().correct()).isEqualTo(1);
        assertThat(detail.recentLiveCalls()).extracting(AdminPlayerDetail.LiveCall::correct)
                .containsExactly(null, true);   // newest first: the open one has no verdict
    }

    @Test
    void anUnknownAccountIsAnErrorRatherThanAnEmptyDetail() {
        assertThatThrownBy(() -> service.detail(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#-1");
    }
}
