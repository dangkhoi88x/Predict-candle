package com.example.candles.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.example.candles.domain.TelegramUser;
import com.example.candles.exception.InvalidCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramInitDataVerifierTest {

    private static final String BOT_TOKEN = "7000000001:AAtestTokenForUnitTestsOnly_0123456789";
    private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    private TelegramInitDataVerifier verifier(String token) {
        return new TelegramInitDataVerifier(token, Duration.ofHours(24),
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    @Test
    void aLaunchTelegramSignedNamesItsUser() {
        String initData = TelegramInitDataFixture.signed(BOT_TOKEN, 123456789L, "lan_trader", NOW.minusSeconds(30));

        TelegramUser user = verifier(BOT_TOKEN).verify(initData);

        assertThat(user.id()).isEqualTo(123456789L);
        assertThat(user.username()).isEqualTo("lan_trader");
        assertThat(user.displayName()).isEqualTo("@lan_trader");
    }

    @Test
    void withoutAUsernameTheNameIsWhatTelegramShows() {
        String initData = TelegramInitDataFixture.signed(BOT_TOKEN, 42L, null, NOW);

        assertThat(verifier(BOT_TOKEN).verify(initData).displayName()).isEqualTo("Lan Nguyễn");
    }

    @Test
    void changingAnyFieldBreaksTheSignature() {
        String initData = TelegramInitDataFixture.signed(BOT_TOKEN, 42L, "lan", NOW);
        String someoneElse = initData.replace("%22id%22%3A42", "%22id%22%3A43");
        assertThat(someoneElse).isNotEqualTo(initData);

        assertThatThrownBy(() -> verifier(BOT_TOKEN).verify(someoneElse))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aLaunchSignedForAnotherBotIsRefused() {
        String initData = TelegramInitDataFixture.signed("7000000002:AAanotherBotEntirely_9876543210", 42L, "lan", NOW);

        assertThatThrownBy(() -> verifier(BOT_TOKEN).verify(initData))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void anOldLaunchIsNotALoginForever() {
        String initData = TelegramInitDataFixture.signed(BOT_TOKEN, 42L, "lan", NOW.minus(Duration.ofHours(25)));

        assertThatThrownBy(() -> verifier(BOT_TOKEN).verify(initData))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void missingOrMalformedPiecesAreRefusedNotCrashedOn() {
        TelegramInitDataVerifier verifier = verifier(BOT_TOKEN);
        String valid = TelegramInitDataFixture.signed(BOT_TOKEN, 42L, "lan", NOW);

        for (String bad : new String[] {
                "", "garbage", valid.replaceAll("&hash=[0-9a-f]+", ""), valid.replaceAll("hash=[0-9a-f]+", "hash=zz"),
                TelegramInitDataFixture.sign(BOT_TOKEN, Map.of("auth_date", Long.toString(NOW.getEpochSecond()))),
                TelegramInitDataFixture.sign(BOT_TOKEN, Map.of("auth_date", "soon", "user", "{\"id\":1}")),
                TelegramInitDataFixture.sign(BOT_TOKEN, Map.of("auth_date", Long.toString(NOW.getEpochSecond()), "user", "not json")),
        }) {
            assertThatThrownBy(() -> verifier.verify(bad)).as(bad).isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Test
    void withoutABotTokenNothingVerifies() {
        TelegramInitDataVerifier off = verifier("");
        String initData = TelegramInitDataFixture.signed("", 42L, "lan", NOW);

        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.verify(initData)).isInstanceOf(InvalidCredentialsException.class);
    }
}
