package com.example.candles.domain;

/** The Telegram account a verified Mini App launch belongs to. Never leaves the server. */
public record TelegramUser(long id, String username, String firstName, String lastName) {

    /** "@username" when there is one, else the first and last name, never blank. */
    public String displayName() {
        String name = username != null && !username.isBlank() ? "@" + username
                : ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        if (name.isBlank()) name = "Telegram " + id;
        return name.length() > 64 ? name.substring(0, 64) : name;
    }
}
