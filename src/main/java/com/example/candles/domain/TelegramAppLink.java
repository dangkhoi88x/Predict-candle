package com.example.candles.domain;

import java.util.regex.Pattern;

/**
 * The t.me link that opens the Mini App — {@code https://t.me/<bot>/<app>} — built from the two
 * names BotFather gave out, never from a URL in configuration, so a mistyped variable cannot make
 * the page or the bot link anywhere else.
 */
public final class TelegramAppLink {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,64}");

    private TelegramAppLink() {
    }

    /** Null unless both names are valid. */
    public static String of(String botUsername, String appName) {
        String bot = botUsername == null ? "" : botUsername.trim().replaceFirst("^@", "");
        String app = appName == null ? "" : appName.trim();
        return NAME.matcher(bot).matches() && NAME.matcher(app).matches()
                ? "https://t.me/" + bot + "/" + app : null;
    }
}
