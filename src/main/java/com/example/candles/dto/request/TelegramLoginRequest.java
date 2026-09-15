package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code Telegram.WebApp.initData}, exactly as Telegram gave it to the page. */
public record TelegramLoginRequest(@NotBlank @Size(max = 4096) String initData) {
}
