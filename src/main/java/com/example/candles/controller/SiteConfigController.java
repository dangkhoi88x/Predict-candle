package com.example.candles.controller;

import com.example.candles.domain.TelegramAppLink;
import com.example.candles.dto.response.SiteConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Per-deployment switches for the static front end. Public: it holds nothing a visitor could not
 * read off the page once the script it names has loaded.
 *
 * The analytics site is configured by its GoatCounter code alone ({@code ANALYTICS_GOATCOUNTER},
 * e.g. {@code candle-guess}) and the endpoint is built here. Accepting a whole URL instead would
 * turn a mistyped variable into the page loading a script from wherever it pointed.
 */
@RestController
@RequestMapping("/api/site-config")
public class SiteConfigController {

    private static final Pattern GOATCOUNTER_CODE = Pattern.compile("[a-z0-9][a-z0-9-]{1,48}[a-z0-9]");

    private final SiteConfigResponse config;

    public SiteConfigController(@Value("${candles.analytics.goatcounter:}") String goatcounterCode,
                                @Value("${candles.telegram.bot-token:}") String telegramBotToken,
                                @Value("${candles.telegram.bot-username:}") String telegramBotUsername,
                                @Value("${candles.telegram.app-name:}") String telegramAppName) {
        this.config = new SiteConfigResponse(goatcounterEndpoint(goatcounterCode),
                telegram(telegramBotToken, telegramBotUsername, telegramAppName));
    }

    /** Names only, validated like the GoatCounter code, so neither can make the page link anywhere else. */
    static SiteConfigResponse.Telegram telegram(String botToken, String botUsername, String appName) {
        boolean login = botToken != null && !botToken.isBlank();
        String link = TelegramAppLink.of(botUsername, appName);
        return login || link != null ? new SiteConfigResponse.Telegram(login, link) : null;
    }

    static String goatcounterEndpoint(String code) {
        if (code == null) return null;
        String trimmed = code.trim().toLowerCase();
        if (!GOATCOUNTER_CODE.matcher(trimmed).matches()) return null;
        return "https://" + trimmed + ".goatcounter.com/count";
    }

    /** Cached briefly: it changes only with a redeploy, and every page load asks. */
    @GetMapping
    public ResponseEntity<SiteConfigResponse> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(config);
    }
}
