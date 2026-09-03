package com.example.candles.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.candles.dto.response.Leaderboard;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeaderboardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LeaderboardService leaderboard;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private GuessResultRepository guessResults;

    /* The board caches for a minute, which is right in production and wrong across tests
       sharing one JVM: the second test would read the first one's ranking. */
    @BeforeEach
    void freshCache() {
        leaderboard.evict();
    }

    /** A player with {@code correct} winners followed by {@code wrong} losers. */
    private User player(String name, int correct, int wrong) {
        User user = users.saveAndFlush(
                new User("0x" + UUID.randomUUID().toString().replace("-", ""), name));
        Asset asset = assets.findAll().getFirst();
        List<GuessResult> batch = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < correct; i++) {
            batch.add(new GuessResult(user, asset, "1h", index++, 1, Direction.LONG, Direction.LONG));
        }
        for (int i = 0; i < wrong; i++) {
            batch.add(new GuessResult(user, asset, "1h", index++, 1, Direction.LONG, Direction.SHORT));
        }
        guessResults.saveAll(batch);
        guessResults.flush();
        return user;
    }

    private Leaderboard.Row rowFor(Leaderboard board, String name) {
        return board.rows().stream().filter(r -> r.displayName().equals(name)).findFirst().orElse(null);
    }

    @Test
    void ranksByScoreAndCarriesAccuracyAlongside() {
        String strong = "strong-" + UUID.randomUUID();
        String weak = "weak-" + UUID.randomUUID();
        player(strong, 30, 5);
        player(weak, 21, 14);

        Leaderboard board = leaderboard.board(50, null);

        Leaderboard.Row a = rowFor(board, strong), b = rowFor(board, weak);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.score()).isGreaterThan(b.score());
        assertThat(a.rank()).isLessThan(b.rank());
        // Accuracy travels with the row so a reader can tell a sharp player from a busy one.
        assertThat(a.accuracy()).isGreaterThan(b.accuracy());
        assertThat(a.correct()).isEqualTo(30);
        assertThat(a.total()).isEqualTo(35);
    }

    /**
     * The seeded/dev admin wallet plays far more rounds than a real player while the app is
     * being tested, and a public board putting staff in first place reads as gaming their own
     * leaderboard. An admin who otherwise qualifies must still be absent.
     */
    @Test
    void anAdminAccountNeverAppearsOnTheBoardEvenWhenItQualifies() {
        String admin = "admin-" + UUID.randomUUID();
        User adminUser = player(admin, 40, 0); // easily the top score, if it were eligible
        adminUser.assignRole(Role.ADMIN);
        users.saveAndFlush(adminUser);
        String real = "real-" + UUID.randomUUID();
        player(real, LeaderboardService.MIN_GUESSES, 0);
        leaderboard.evict();

        Leaderboard board = leaderboard.board(50, adminUser.getId());

        assertThat(rowFor(board, admin)).isNull();
        assertThat(rowFor(board, real)).isNotNull();
        // "You are rank N" makes no sense for someone who was deliberately left off the board.
        assertThat(board.me()).isNull();
    }

    @Test
    void aPlayerBelowTheThresholdIsNotRanked() {
        String tiny = "tiny-" + UUID.randomUUID();
        // A perfect record over too few guesses is a perfect record and a meaningless one.
        player(tiny, LeaderboardService.MIN_GUESSES - 1, 0);

        assertThat(rowFor(leaderboard.board(50, null), tiny)).isNull();
    }

    /**
     * The reason this endpoint exists in this shape.
     *
     * `legacy_*` is a tally the browser reported at first sign-in — client-supplied, checked
     * only for coherence. If it counted here, the fastest way up the board would be to post a
     * large believable number rather than to play.
     */
    @Test
    void selfReportedLegacyStatsCannotBuyRank() {
        String honest = "honest-" + UUID.randomUUID();
        String claimant = "claimant-" + UUID.randomUUID();
        player(honest, 30, 5);

        User cheat = player(claimant, 5, 25);
        cheat.importLegacyStats(9_000, 9_000, 180_000, 500);
        users.saveAndFlush(cheat);
        leaderboard.evict();

        Leaderboard board = leaderboard.board(50, null);
        Leaderboard.Row a = rowFor(board, honest), b = rowFor(board, claimant);

        assertThat(a.rank()).isLessThan(b.rank());
        // The row shows what the server scored, not what the client claimed.
        assertThat(b.total()).isEqualTo(30);
        assertThat(b.correct()).isEqualTo(5);
        assertThat(b.score()).isLessThan(a.score());
        assertThat(b.score()).isLessThan(180_000);
    }

    @Test
    void meResolvesAgainstTheWholeBoardNotThePage() {
        String top = "top-" + UUID.randomUUID();
        String mid = "mid-" + UUID.randomUUID();
        player(top, 40, 0);
        User self = player(mid, 20, 10);

        // A page of one: the caller is nowhere in `rows`, and still learns their rank.
        Leaderboard board = leaderboard.board(1, self.getId());

        assertThat(board.rows()).hasSize(1);
        assertThat(board.rows().getFirst().displayName()).isEqualTo(top);
        assertThat(board.me()).isNotNull();
        assertThat(board.me().displayName()).isEqualTo(mid);
        assertThat(board.me().rank()).isGreaterThan(1);
    }

    @Test
    void theBoardIsPublicAndAnonymousCallersJustHaveNoMeRow() throws Exception {
        player("public-" + UUID.randomUUID(), 25, 5);
        leaderboard.evict();

        mockMvc.perform(get("/api/leaderboard?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minGuesses").value(LeaderboardService.MIN_GUESSES))
                .andExpect(jsonPath("$.rows").isArray())
                .andExpect(jsonPath("$.me").doesNotExist());
    }

    @Test
    void theBoardNeverExposesAWalletAddress() throws Exception {
        player("private-" + UUID.randomUUID(), 25, 5);
        leaderboard.evict();

        String body = mockMvc.perform(get("/api/leaderboard?limit=50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Display names default to a shortened wallet; a full 42-character address must never
        // appear, since this is the first thing in the app one user can read about another.
        assertThat(body).doesNotContain("walletAddress");
        assertThat(body).doesNotMatch("(?s).*0x[0-9a-fA-F]{40}.*");
    }
}
