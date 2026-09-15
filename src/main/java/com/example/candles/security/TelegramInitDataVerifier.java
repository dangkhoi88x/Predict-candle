package com.example.candles.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.example.candles.domain.TelegramUser;
import com.example.candles.exception.InvalidCredentialsException;

/**
 * Checks the {@code initData} Telegram hands a Mini App on launch, the way Telegram documents it:
 * every field but {@code hash}, sorted by key, joined as {@code key=value} lines, HMAC-SHA256'd
 * with a key that is itself HMAC-SHA256("WebAppData", bot token). A match proves Telegram signed
 * this launch for this bot, so the {@code user} inside it is who is really playing.
 *
 * The bot token is the whole secret, which is why it only ever comes from the environment
 * ({@code TELEGRAM_BOT_TOKEN}) and why this is off — {@link #enabled()} false — without it.
 *
 * {@code auth_date} is checked too: initData is the same string for as long as a launch lasts, and
 * a copied one should not be a login forever.
 */
@Component
public class TelegramInitDataVerifier {

    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final byte[] secretKey;
    private final Duration maxAge;
    private final Clock clock;
    private final ObjectMapper json;

    public TelegramInitDataVerifier(@Value("${candles.telegram.bot-token:}") String botToken,
                                    @Value("${candles.telegram.init-data-max-age:PT24H}") Duration maxAge,
                                    Clock clock, ObjectMapper json) {
        this.secretKey = botToken == null || botToken.isBlank() ? null
                : hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.trim());
        this.maxAge = maxAge;
        this.clock = clock;
        this.json = json;
    }

    static final String INVALID_MESSAGE =
            "Không xác thực được lượt mở từ Telegram. Hãy đóng game rồi mở lại từ Telegram.";

    private static InvalidCredentialsException invalid() {
        return new InvalidCredentialsException(INVALID_MESSAGE);
    }

    public boolean enabled() {
        return secretKey != null;
    }

    public TelegramUser verify(String initData) {
        if (!enabled() || initData == null || initData.isBlank()) throw invalid();

        Map<String, String> fields = new TreeMap<>();
        for (String pair : initData.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            fields.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        String hash = fields.remove("hash");
        if (hash == null) throw invalid();

        String dataCheckString = fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        byte[] expected = hmac(secretKey, dataCheckString);
        byte[] given;
        try {
            given = HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(expected, given)) throw invalid();

        Instant authDate;
        try {
            authDate = Instant.ofEpochSecond(Long.parseLong(fields.get("auth_date")));
        } catch (RuntimeException e) {
            throw invalid();
        }
        Instant now = clock.instant();
        if (authDate.isBefore(now.minus(maxAge)) || authDate.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw invalid();
        }

        try {
            JsonNode user = json.readTree(fields.get("user"));
            long id = user.path("id").asLong(0);
            if (id <= 0) throw invalid();
            return new TelegramUser(id, text(user, "username"), text(user, "first_name"), text(user, "last_name"));
        } catch (InvalidCredentialsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
