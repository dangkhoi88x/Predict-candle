package com.example.candles.service;

import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.LiveRound;
import com.example.candles.dto.response.AdminLiveRoundDetail;
import com.example.candles.dto.response.AdminLiveRounds;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Direction;
import com.example.candles.entity.LivePrediction;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin's view of a game that stores no rounds, and the one action it offers.
 *
 * {@link Clock} is pinned for the reason {@code LiveRoundFlowTest} documents at length: the
 * service reads the clock to decide whether a round has closed, and a test that read the wall
 * clock separately would agree with it until a build happened to run on the wrong side of an
 * hour boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminLiveRoundTest {

    /** Well inside an hour, so no assertion here depends on where in the hour the build runs. */
    private static final Instant FIXED_NOW = Instant.parse("2024-03-02T10:15:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminLiveRoundService service;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private LivePredictionRepository predictions;
    @Autowired private UserRepository users;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;
    @MockitoBean private Clock clock;

    @BeforeEach
    void pinTheClock() {
        when(clock.instant()).thenReturn(FIXED_NOW);
    }

    private Asset asset() {
        return assets.saveAndFlush(new Asset(
                "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Test pair", AssetType.CRYPTO));
    }

    private User user(Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "Người chơi");
        user.assignRole(role);
        return users.saveAndFlush(user);
    }

    /** The round that opened {@code hoursAgo} hours before the pinned clock. */
    private LiveRound past(int hoursAgo) {
        return LiveRound.at(FIXED_NOW.minusSeconds(hoursAgo * 3600L),
                properties.timeframe(), properties.live().lockBefore());
    }

    private void settle(Asset asset, LiveRound round, String open, String close) {
        candles.saveAndFlush(new Candle(asset, properties.timeframe(), round.openTime(),
                new BigDecimal(open), new BigDecimal(close), new BigDecimal(open),
                new BigDecimal(close), BigDecimal.TEN));
    }

    private void call(User user, Asset asset, LiveRound round, Direction direction) {
        predictions.saveAndFlush(new LivePrediction(
                user, asset, properties.timeframe(), round.openTime(), direction));
    }

    @Test
    void theListHoldsOnlyRoundsSomebodyCalledAndScoresThemAgainstTheStoredCandle() {
        Asset asset = asset();
        LiveRound played = past(3);
        LiveRound ignored = past(2);

        settle(asset, played, "100", "110");     // closed up: LONG was right
        settle(asset, ignored, "100", "90");     // nobody called this one
        call(user(Role.USER), asset, played, Direction.LONG);
        call(user(Role.USER), asset, played, Direction.SHORT);

        AdminLiveRounds rounds = service.rounds(asset.getSymbol(), null);

        // A round exists every hour whether or not anyone showed up; only the played one is here.
        assertThat(rounds.rounds()).hasSize(1);
        AdminLiveRounds.Round round = rounds.rounds().getFirst();
        assertThat(round.number()).isEqualTo(played.number());
        assertThat(round.settled()).isTrue();
        assertThat(round.result()).isEqualTo("LONG");
        assertThat(round.calls()).isEqualTo(2);
        assertThat(round.longCount()).isEqualTo(1);
        assertThat(round.shortCount()).isEqualTo(1);
        assertThat(round.correct()).isEqualTo(1);
    }

    /**
     * The row this pane exists to surface. A closed round with no candle behind it is not a
     * cosmetic gap: those calls score nothing anywhere, because every other reader of live
     * results makes the same join and makes it an inner one.
     */
    @Test
    void aRoundWhoseCandleNeverArrivedReadsAsUnsettledAndScoresNobody() {
        Asset asset = asset();
        LiveRound orphan = past(4);
        call(user(Role.USER), asset, orphan, Direction.LONG);

        AdminLiveRounds.Round round = service.rounds(asset.getSymbol(), null).rounds().getFirst();

        assertThat(round.settled()).isFalse();
        assertThat(round.result()).isNull();
        assertThat(round.open()).isNull();
        assertThat(round.calls()).isEqualTo(1);
        // Zero, not null: nobody has been marked right, which is a real answer rather than an
        // unknown one.
        assertThat(round.correct()).isZero();

        // And the call itself is still visible, with no verdict attached to it.
        AdminLiveRoundDetail detail = service.detail(asset.getSymbol(), round.number());
        assertThat(detail.settled()).isFalse();
        assertThat(detail.calls()).singleElement()
                .satisfies(c -> assertThat(c.correct()).isNull());
    }

    /**
     * The public roster deliberately never carries an address — a display name defaults to a
     * shortened wallet precisely so an open page cannot be scraped for them. An admin who
     * opened a round did so to find an account, and a shortened address is not something you
     * can act on.
     */
    @Test
    void theAdminRosterCarriesTheAddressThePublicOneWithholds() {
        Asset asset = asset();
        LiveRound round = past(3);
        settle(asset, round, "100", "90");
        User player = user(Role.USER);
        call(player, asset, round, Direction.SHORT);

        AdminLiveRoundDetail detail = service.detail(asset.getSymbol(), round.number());

        assertThat(detail.result()).isEqualTo("SHORT");
        assertThat(detail.calls()).singleElement().satisfies(c -> {
            assertThat(c.walletAddress()).isEqualTo(player.getWalletAddress());
            assertThat(c.userId()).isEqualTo(player.getId());
            assertThat(c.correct()).isTrue();
        });
    }

    /**
     * Voiding is a delete, not a flag, and the point of the test is that the round stops
     * existing everywhere rather than being hidden from one list: the rows are what score, rank
     * and streak are all folded out of, so removing them is the only thing that reaches all of
     * them at once.
     */
    @Test
    void voidingARoundRemovesItsCallsAndTheRoundWithThem() {
        Asset asset = asset();
        LiveRound round = past(3);
        settle(asset, round, "100", "110");
        call(user(Role.USER), asset, round, Direction.LONG);
        call(user(Role.USER), asset, round, Direction.SHORT);

        assertThat(service.voidRound(asset.getSymbol(), round.number(), null)).isEqualTo(2);

        assertThat(service.rounds(asset.getSymbol(), null).rounds()).isEmpty();
        assertThat(predictions.countSides(asset.getId(), properties.timeframe(), round.openTime()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(((Number) row[0]).intValue()).isZero();
                    assertThat(((Number) row[1]).intValue()).isZero();
                });
    }

    /** A round nobody played is a no-op rather than an error: nothing to remove is not a failure. */
    @Test
    void voidingARoundNobodyCalledRemovesNothingAndSaysSo() {
        Asset asset = asset();
        assertThat(service.voidRound(asset.getSymbol(), past(5).number(), null)).isZero();
    }

    @Test
    void everyRouteIsClosedToEveryoneButAdmins() throws Exception {
        Asset asset = asset();
        String url = "/api/admin/live/rounds?asset=" + asset.getSymbol();

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + jwt.createAccessToken(user(Role.USER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + jwt.createAccessToken(user(Role.ADMIN))))
                .andExpect(status().isOk());

        String void_ = "/api/admin/live/rounds/" + past(3).number() + "?asset=" + asset.getSymbol();
        mockMvc.perform(delete(void_)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(void_).header("Authorization", "Bearer " + jwt.createAccessToken(user(Role.USER))))
                .andExpect(status().isForbidden());
    }
}
