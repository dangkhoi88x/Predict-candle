package com.example.candles.controller;

import com.example.candles.client.CandleData;
import com.example.candles.client.PriceDataProvider;
import com.example.candles.config.CandlesProperties;
import com.example.candles.config.ClockConfig;
import com.example.candles.domain.LiveRound;
import com.example.candles.security.JwtService;
import com.example.candles.entity.Asset;
import com.example.candles.entity.AssetType;
import com.example.candles.entity.Candle;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;
import com.example.candles.repository.LivePredictionRepository;
import com.example.candles.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The live round over HTTP: the candle the exchange is forming right now, called by whoever
 * shows up while it is still open.
 *
 * {@link PriceDataProvider} is mocked rather than left to reach Binance — a live round asks for
 * the still-forming candle on every read, so a real dependency here would be the network call
 * PracticeRoundFlowTest already learned not to make, just on the hot path instead of a setup step.
 *
 * {@link Clock} is mocked too, and pinned well inside a round's open window. This class used to
 * read the wall clock with {@code Instant.now()} the same as {@link LiveRoundService} does — two
 * independent reads of the same clock, fine right up until a build happened to run in the ~8
 * minutes of every hour a round is locked ({@code candles.live.lock-before}), where a predict()
 * call this test expected to succeed came back 400 instead. That reached CI on main, not just
 * here: nothing local ever ran in that window before it did. Fixing the flake meant the test and
 * the service could no longer be allowed to each read time on their own — see {@link ClockConfig}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LiveRoundFlowTest {

    /** The 15th minute of an arbitrary hour: far from both the open and the 8-minute-before-close
        lock, so the choice of instant is not itself load-bearing. */
    private static final Instant FIXED_NOW = Instant.parse("2024-01-01T10:15:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private CandleRepository candles;
    @Autowired private LivePredictionRepository predictions;
    @Autowired private CandlesProperties properties;
    @Autowired private JwtService jwt;
    @MockitoBean private PriceDataProvider priceDataProvider;
    @MockitoBean private Clock clock;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void pinTheClock() {
        when(clock.instant()).thenReturn(FIXED_NOW);
    }

    private Asset seedAsset() {
        return assets.saveAndFlush(new Asset(
                "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "Test pair", AssetType.CRYPTO));
    }

    private User player() {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "P");
        user.assignRole(Role.USER);
        return users.saveAndFlush(user);
    }

    private LiveRound currentRound(String timeframe) {
        return LiveRound.at(FIXED_NOW, timeframe, properties.live().lockBefore());
    }

    /** Stands in for Binance while a round is still open: PriceDataProvider is asked, not the DB. */
    private void stubForming(String symbol, String timeframe, Instant openTime, BigDecimal open, BigDecimal last) {
        when(priceDataProvider.fetchCandles(org.mockito.ArgumentMatchers.eq(symbol), anyString(), any(), any()))
                .thenReturn(List.of(new CandleData(openTime, open, last, open, last, BigDecimal.TEN)));
    }

    private MvcResult getRound(String symbol, String bearer) throws Exception {
        var request = get("/api/live/round?asset=" + symbol);
        if (bearer != null) request = request.header("Authorization", bearer);
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult predict(String symbol, String direction, String bearer) throws Exception {
        String body = "{\"asset\":\"" + symbol + "\",\"direction\":\"" + direction + "\"}";
        var request = post("/api/live/predict").contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) request = request.header("Authorization", bearer);
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void theOpenRoundReflectsTheStillFormingCandleAndNeverLeaksAnAnswer() throws Exception {
        Asset asset = seedAsset();
        LiveRound round = currentRound(properties.timeframe());
        stubForming(asset.getSymbol(), properties.timeframe(), round.openTime(),
                new BigDecimal("100.00"), new BigDecimal("103.50"));

        MvcResult result = getRound(asset.getSymbol(), null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode round0 = mapper.readTree(result.getResponse().getContentAsString());

        assertThat(round0.path("asset").asString()).isEqualTo(asset.getSymbol());
        assertThat(round0.path("roundNumber").asLong()).isEqualTo(round.number());
        assertThat(round0.path("openPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(round0.path("livePrice").decimalValue()).isEqualByComparingTo("103.50");
        assertThat(round0.path("locked").asBoolean()).isEqualTo(round.isLocked(FIXED_NOW));
        assertThat(round0.path("myDirection").isNull()).isTrue();
        assertThat(round0.path("longCount").asInt()).isZero();
        assertThat(round0.path("shortCount").asInt()).isZero();
    }

    @Test
    void anonymousReadingIsFineButPredictingNeedsAWallet() throws Exception {
        Asset asset = seedAsset();
        LiveRound round = currentRound(properties.timeframe());
        stubForming(asset.getSymbol(), properties.timeframe(), round.openTime(),
                BigDecimal.TEN, BigDecimal.TEN);

        assertThat(predict(asset.getSymbol(), "LONG", null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aSignedInCallIsRecordedAndShowsUpOnTheNextRead() throws Exception {
        Asset asset = seedAsset();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);
        LiveRound round = currentRound(properties.timeframe());
        stubForming(asset.getSymbol(), properties.timeframe(), round.openTime(),
                BigDecimal.TEN, BigDecimal.TEN);

        MvcResult result = predict(asset.getSymbol(), "LONG", bearer);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode after = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(after.path("myDirection").asString()).isEqualTo("LONG");
        assertThat(after.path("longCount").asInt()).isEqualTo(1);

        assertThat(predictions.findByUserOrderByOpenTimeDesc(
                users.findById(player.getId()).orElseThrow())).hasSize(1);
    }

    @Test
    void callingTheSameRoundTwiceIsRefused() throws Exception {
        Asset asset = seedAsset();
        User player = player();
        String bearer = "Bearer " + jwt.createAccessToken(player);
        LiveRound round = currentRound(properties.timeframe());
        stubForming(asset.getSymbol(), properties.timeframe(), round.openTime(),
                BigDecimal.TEN, BigDecimal.TEN);

        assertThat(predict(asset.getSymbol(), "LONG", bearer).getResponse().getStatus()).isEqualTo(200);
        assertThat(predict(asset.getSymbol(), "SHORT", bearer).getResponse().getStatus()).isEqualTo(400);

        assertThat(predictions.findByUserOrderByOpenTimeDesc(
                users.findById(player.getId()).orElseThrow())).hasSize(1);
    }

    /**
     * A settled round reads its outcome from the stored candle rather than the (mocked, and here
     * uncalled) price provider — proof that {@link com.example.candles.service.LiveRoundService}
     * really does stop asking the exchange once history has the answer.
     */
    @Test
    void aClosedRoundShowsUpInHistoryWithItsResult() throws Exception {
        Asset asset = seedAsset();
        String timeframe = properties.timeframe();
        LiveRound previous = currentRound(timeframe).previous(timeframe, properties.live().lockBefore());
        candles.saveAndFlush(new Candle(asset, timeframe, previous.openTime(),
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("95"), new BigDecimal("108"),
                new BigDecimal("500")));

        MvcResult result = mockMvc.perform(get("/api/live/history?asset=" + asset.getSymbol())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode history = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode rounds = history.path("rounds");

        assertThat(rounds).hasSize(1);
        assertThat(rounds.get(0).path("roundNumber").asLong()).isEqualTo(previous.number());
        assertThat(rounds.get(0).path("result").asString()).isEqualTo("LONG");
        assertThat(rounds.get(0).path("closePrice").decimalValue()).isEqualByComparingTo("108");
    }

    @Test
    void anUnknownPairCannotBeRead() throws Exception {
        mockMvc.perform(get("/api/live/round?asset=NOPEUSDT"))
                .andReturn();
        int status = mockMvc.perform(get("/api/live/round?asset=NOPEUSDT")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }
}
