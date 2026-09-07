package com.example.candles.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.candles.dto.response.AdminRetention;
import com.example.candles.entity.Asset;
import com.example.candles.entity.Direction;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.GuessResult;
import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retention has two ways to be quietly wrong, and both are here.
 *
 * A cohort that joined yesterday cannot have come back within a week yet. Counting it as a
 * cohort that failed to come back means every new player pushes the reported rate down — the
 * measurement would say the site is losing people precisely when it is gaining them.
 *
 * And the seeded admin account plays constantly while the app is being worked on. Left in, it
 * is a player who returns every single day, and at this scale that one account would carry the
 * whole figure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRetentionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwt;
    @Autowired private AdminRetentionService retention;
    @Autowired private UserRepository users;
    @Autowired private AssetRepository assets;
    @Autowired private GuessResultRepository guessResults;
    @Autowired private EntityManager entityManager;

    private User player(Role role) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "R");
        user.assignRole(role);
        return users.saveAndFlush(user);
    }

    /** One recorded guess dated {@code daysAgo} days back, the way a real play would sit. */
    private void playedDaysAgo(User user, int startIndex, int daysAgo) {
        Asset asset = assets.findAll().getFirst();
        GuessResult saved = guessResults.saveAndFlush(
                new GuessResult(user, asset, "1h", startIndex, 1, Direction.LONG, Direction.LONG, GuessMode.PRACTICE));
        entityManager.createNativeQuery("update guess_results set created_at = :at where id = :id")
                // Midday, so a few hours either way cannot tip a row into the wrong UTC date.
                .setParameter("at", LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo)
                        .atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant())
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();
    }

    private AdminRetention read() {
        // fresh=true: the service caches for a minute, and every test here writes new rows.
        return retention.retention(30, true);
    }

    private AdminRetention.Cohort cohortFor(AdminRetention result, int daysAgo) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo);
        return result.cohorts().stream()
                .filter(c -> c.day().equals(day))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No cohort for " + day));
    }

    @Test
    void aPlayerWhoCameBackTheNextDayCountsAsReturned() {
        User user = player(Role.USER);
        playedDaysAgo(user, 1, 10);
        playedDaysAgo(user, 2, 9);

        AdminRetention.Cohort cohort = cohortFor(read(), 10);

        assertThat(cohort.returnedNextDay()).isGreaterThanOrEqualTo(1);
        assertThat(cohort.returnedWithinWeek()).isGreaterThanOrEqualTo(1);
        assertThat(cohort.nextDayMature()).isTrue();
        assertThat(cohort.withinWeekMature()).isTrue();
    }

    @Test
    void playingTwiceOnTheFirstDayIsNotAReturn() {
        User user = player(Role.USER);
        playedDaysAgo(user, 1, 10);
        playedDaysAgo(user, 2, 10);

        AdminRetention before = read();
        long returned = cohortFor(before, 10).returnedNextDay();

        // Another account that did the same thing must not move the returned count either.
        User second = player(Role.USER);
        playedDaysAgo(second, 3, 10);

        AdminRetention after = read();
        assertThat(cohortFor(after, 10).newPlayers()).isEqualTo(cohortFor(before, 10).newPlayers() + 1);
        assertThat(cohortFor(after, 10).returnedNextDay()).isEqualTo(returned);
    }

    @Test
    void todaysCohortIsNotYetJudgedOnWhetherItCameBack() {
        User user = player(Role.USER);
        playedDaysAgo(user, 1, 0);

        AdminRetention result = read();
        AdminRetention.Cohort today = cohortFor(result, 0);

        assertThat(today.newPlayers()).isGreaterThanOrEqualTo(1);
        assertThat(today.nextDayMature()).isFalse();
        assertThat(today.withinWeekMature()).isFalse();
        // And it must stay out of the denominators, or a brand new player reads as a lost one.
        assertThat(result.summary().nextDayEligible()).isLessThan(result.summary().newPlayers());
    }

    @Test
    void aCohortThreeDaysOldCountsForTheNextDayWindowButNotTheWeek() {
        User user = player(Role.USER);
        playedDaysAgo(user, 1, 3);

        AdminRetention.Cohort cohort = cohortFor(read(), 3);

        assertThat(cohort.nextDayMature()).isTrue();
        assertThat(cohort.withinWeekMature()).isFalse();
    }

    @Test
    void adminAccountsAreLeftOutEntirely() {
        AdminRetention before = read();

        User admin = player(Role.ADMIN);
        playedDaysAgo(admin, 1, 10);
        playedDaysAgo(admin, 2, 9);
        playedDaysAgo(admin, 3, 8);

        AdminRetention after = read();

        assertThat(after.summary().newPlayers()).isEqualTo(before.summary().newPlayers());
        assertThat(after.summary().plays()).isEqualTo(before.summary().plays());
    }

    /**
     * The pane reads these names out of the JSON and there is no shared schema between the two,
     * so a renamed record component would leave it drawing dashes with nothing failing. Same
     * reason {@code AdminStatsTest} pins its own field names.
     */
    @Test
    void theJsonCarriesTheNamesThePaneReads() throws Exception {
        User admin = player(Role.ADMIN);
        String bearer = "Bearer " + jwt.createAccessToken(admin);

        mockMvc.perform(get("/api/admin/retention?fresh=true").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").exists())
                .andExpect(jsonPath("$.to").exists())
                .andExpect(jsonPath("$.summary.newPlayers").exists())
                .andExpect(jsonPath("$.summary.nextDayEligible").exists())
                .andExpect(jsonPath("$.summary.returnedNextDay").exists())
                .andExpect(jsonPath("$.summary.withinWeekEligible").exists())
                .andExpect(jsonPath("$.summary.returnedWithinWeek").exists())
                .andExpect(jsonPath("$.summary.plays").exists())
                .andExpect(jsonPath("$.summary.activePlayerDays").exists())
                .andExpect(jsonPath("$.cohorts").isArray())
                .andExpect(jsonPath("$.daily").isArray());
    }

    @Test
    void aSignedOutVisitorCannotReadIt() throws Exception {
        mockMvc.perform(get("/api/admin/retention")).andExpect(status().isUnauthorized());
    }

    @Test
    void anOrdinaryPlayerCannotReadIt() throws Exception {
        String bearer = "Bearer " + jwt.createAccessToken(player(Role.USER));

        mockMvc.perform(get("/api/admin/retention").header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void playsPerActivePlayerDayHasBothItsNumbers() {
        User user = player(Role.USER);
        playedDaysAgo(user, 1, 5);
        playedDaysAgo(user, 2, 5);
        playedDaysAgo(user, 3, 4);

        AdminRetention result = read();

        // Three calls over two active days for this player; other rows in the database only add
        // to both sides, so the relationship — never more days than calls — has to hold.
        assertThat(result.summary().plays()).isGreaterThanOrEqualTo(3);
        assertThat(result.summary().activePlayerDays()).isGreaterThanOrEqualTo(2);
        assertThat(result.summary().activePlayerDays()).isLessThanOrEqualTo(result.summary().plays());
    }
}
