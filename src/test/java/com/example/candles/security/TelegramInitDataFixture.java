package com.example.candles.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Signs initData the way Telegram does, so tests can produce launches a real bot would accept. */
public final class TelegramInitDataFixture {

    private TelegramInitDataFixture() {
    }

    public static String signed(String botToken, long userId, String username, Instant authDate) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("auth_date", Long.toString(authDate.getEpochSecond()));
        fields.put("query_id", "AAHdF6IQAAAAAN0XohDhrOrc");
        fields.put("user", "{\"id\":" + userId + ",\"first_name\":\"Lan\",\"last_name\":\"Nguyễn\","
                + (username == null ? "" : "\"username\":\"" + username + "\",")
                + "\"language_code\":\"vi\"}");
        return sign(botToken, fields);
    }

    public static String sign(String botToken, Map<String, String> fields) {
        String dataCheckString = new TreeMap<>(fields).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        byte[] secret = TelegramInitDataVerifier.hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
        String hash = HexFormat.of().formatHex(TelegramInitDataVerifier.hmac(secret, dataCheckString));
        return fields.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&")) + "&hash=" + hash;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
