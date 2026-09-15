package com.example.candles.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramBotClientTest {

    private static final String TOKEN = "7000000001:AAclientTestTokenOnly_zyxwvutsrq";

    private MockRestServiceServer server;
    private TelegramBotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://tg.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TelegramBotClient(builder.build(), TOKEN, new ObjectMapper());
    }

    @Test
    void aMessageIsPostedAsHtmlWithOneUrlButtonAndNoPreview() {
        server.expect(requestTo("http://tg.test/bot" + TOKEN + "/sendMessage"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("""
                        {"chat_id": -1001234, "text": "<b>Thử thách #1</b>", "parse_mode": "HTML",
                         "link_preview_options": {"is_disabled": true},
                         "reply_markup": {"inline_keyboard": [[{"text": "Chơi", "url": "https://t.me/b/play?startapp=daily"}]]}}
                        """))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":7}}", MediaType.APPLICATION_JSON));

        client.sendMessage(-1001234L, "<b>Thử thách #1</b>", "Chơi", "https://t.me/b/play?startapp=daily");

        server.verify();
    }

    @Test
    void telegramsRefusalIsReportedInItsOwnWords() {
        server.expect(requestTo("http://tg.test/bot" + TOKEN + "/sendMessage"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was kicked from the group chat\"}"));

        assertThatThrownBy(() -> client.sendMessage(-1L, "x", null, null))
                .isInstanceOf(TelegramBotClient.TelegramApiException.class)
                .hasMessageContaining("bot was kicked")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    /** Spring's I/O exception quotes the URL, and the URL holds the token. */
    @Test
    void aNetworkFailureNeverCarriesTheTokenIntoTheMessage() {
        server.expect(requestTo("http://tg.test/bot" + TOKEN + "/sendMessage"))
                .andRespond(withException(new IOException("Connection reset")));

        assertThatThrownBy(() -> client.sendMessage(-1L, "x", null, null))
                .isInstanceOf(TelegramBotClient.TelegramApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain("7000000001"));
    }

    @Test
    void withoutATokenNothingIsCalled() {
        TelegramBotClient off = new TelegramBotClient(RestClient.builder().baseUrl("http://tg.test").build(), "", new ObjectMapper());

        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.sendMessage(-1L, "x", null, null))
                .isInstanceOf(TelegramBotClient.TelegramApiException.class);
    }

    @Test
    void recentChatsAreEachChatOnceWhereverTheUpdateMentionedIt() {
        server.expect(requestTo("http://tg.test/bot" + TOKEN + "/getUpdates"))
                .andRespond(withSuccess("""
                        {"ok": true, "result": [
                          {"update_id": 1, "my_chat_member": {"chat": {"id": -1001234, "type": "supergroup", "title": "Crypto VN"}}},
                          {"update_id": 2, "message": {"chat": {"id": 555, "type": "private", "first_name": "Lan"}, "text": "/start"}},
                          {"update_id": 3, "message": {"chat": {"id": -1001234, "type": "supergroup", "title": "Crypto VN"}, "text": "/start@candle_guess_bot"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<TelegramBotClient.Chat> chats = client.recentChats();

        assertThat(chats).containsExactly(
                new TelegramBotClient.Chat(555L, "private", "Lan"),
                new TelegramBotClient.Chat(-1001234L, "supergroup", "Crypto VN"));
    }
}
