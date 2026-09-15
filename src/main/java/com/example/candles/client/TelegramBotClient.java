package com.example.candles.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few Bot API calls the app makes as its bot — posting to a group.
 *
 * <b>The token is in every request's path</b> ({@code /bot<token>/sendMessage}), which is how the
 * Bot API is addressed, and Spring's I/O exceptions quote the URL they failed on. Letting one
 * propagate would copy the bot token into the log and into the ops pane's recent errors, where an
 * admin screenshot would carry it off. So every failure here is rethrown as a
 * {@link TelegramApiException} with a message built from Telegram's own {@code description} or the
 * exception's type, never from the original message.
 */
@Component
public class TelegramBotClient {

    private final RestClient restClient;
    private final String botToken;
    private final ObjectMapper json;

    public TelegramBotClient(RestClient telegramRestClient,
                             @Value("${candles.telegram.bot-token:}") String botToken,
                             ObjectMapper json) {
        this.restClient = telegramRestClient;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.json = json;
    }

    public boolean configured() {
        return !botToken.isEmpty();
    }

    /**
     * Posts {@code html} (Telegram's HTML subset — escape anything a player wrote) to a chat, with
     * one URL button under it. Link previews are off: the button is the link, and a preview card
     * of the site under every reminder would double the message's height in the group.
     */
    public void sendMessage(long chatId, String html, String buttonText, String buttonUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (buttonText != null && buttonUrl != null) {
            body.put("reply_markup", Map.of("inline_keyboard",
                    List.of(List.of(Map.of("text", buttonText, "url", buttonUrl)))));
        }
        call("sendMessage", body);
    }

    private JsonNode call(String method, Map<String, Object> body) {
        if (!configured()) throw new TelegramApiException("Chưa cấu hình TELEGRAM_BOT_TOKEN");
        JsonNode response;
        try {
            response = restClient.post()
                    // Concatenated, not a template variable: a variable is percent-encoded, and
                    // the colon in every bot token would reach Telegram as %3A.
                    .uri("/bot" + botToken + "/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new TelegramApiException("HTTP " + e.getStatusCode().value() + ": "
                    + description(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new TelegramApiException(method + " thất bại (" + e.getClass().getSimpleName() + ")");
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            throw new TelegramApiException(response == null ? "Telegram không trả lời"
                    : response.path("description").asString("Telegram từ chối yêu cầu"));
        }
        return response.path("result");
    }

    private String description(String errorBody) {
        try {
            return json.readTree(errorBody).path("description").asString("không rõ lý do");
        } catch (RuntimeException e) {
            return "không rõ lý do";
        }
    }

    /** A Bot API failure whose message is safe to log: it never contains the request URL. */
    public static class TelegramApiException extends RuntimeException {
        public TelegramApiException(String message) {
            super(message);
        }
    }
}
