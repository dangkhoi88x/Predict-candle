package com.example.candles.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.example.candles.client.TelegramBotClient;
import com.example.candles.config.CandlesProperties;
import com.example.candles.domain.DailyRound;
import com.example.candles.domain.RoundSelection;
import com.example.candles.domain.TelegramAppLink;
import com.example.candles.entity.GuessMode;
import com.example.candles.entity.TelegramBroadcast;
import com.example.candles.entity.User;
import com.example.candles.repository.GuessResultRepository;
import com.example.candles.repository.TelegramBroadcastRepository;

/**
 * The daily challenge, announced in the Telegram groups that asked for it: in the morning that
 * today's round is open, in the evening who is on top so far and how long is left.
 *
 * <b>Opt-in by chat id.</b> Nothing is sent anywhere unless {@code TELEGRAM_DAILY_CHAT_IDS} names
 * the chat. A bot added to a group does not start posting on its own — whoever runs the group
 * agreed to reminders, and the list is where that agreement is written down.
 *
 * <b>Nothing in a message gives the chart away</b>, for the reason the share text holds to: no
 * pair, no dates, only the round number and scores. A reminder that spoils the puzzle for the
 * group it is inviting would be worse than none.
 *
 * <b>Each message is claimed before it is sent</b> ({@code telegram_broadcasts}) — see V19. A
 * send that fails releases its claim and lands in the ops pane's recent errors.
 *
 * The evening standings are a snapshot: the round stays open until UTC midnight (07:00 in
 * Vietnam), so the message says how many hours are still left rather than announcing winners.
 */
@Service
public class TelegramDailyBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(TelegramDailyBroadcastService.class);

    static final int TOP = 3;
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    private final TelegramBotClient bot;
    private final RoundSelectionService rounds;
    private final GuessResultRepository guessResults;
    private final TelegramBroadcastRepository broadcasts;
    private final CandlesProperties properties;
    private final RecentErrors recentErrors;
    private final List<Long> chatIds;
    private final String appLink;

    public TelegramDailyBroadcastService(TelegramBotClient bot,
                                         RoundSelectionService rounds,
                                         GuessResultRepository guessResults,
                                         TelegramBroadcastRepository broadcasts,
                                         CandlesProperties properties,
                                         RecentErrors recentErrors,
                                         @Value("${candles.telegram.daily-chat-ids:}") String chatIds,
                                         @Value("${candles.telegram.bot-username:}") String botUsername,
                                         @Value("${candles.telegram.app-name:}") String appName) {
        this.bot = bot;
        this.rounds = rounds;
        this.guessResults = guessResults;
        this.broadcasts = broadcasts;
        this.properties = properties;
        this.recentErrors = recentErrors;
        this.chatIds = parseChatIds(chatIds);
        this.appLink = TelegramAppLink.of(botUsername, appName);
    }

    /** A token to post with, somewhere to post, and a Mini App for the button to open. */
    public boolean enabled() {
        return bot.configured() && !chatIds.isEmpty() && appLink != null;
    }

    public List<Long> chatIds() {
        return chatIds;
    }

    public enum Outcome { SENT, ALREADY_SENT, FAILED }

    public record ChatResult(long chatId, Outcome outcome, String error) {
    }

    /** What would be posted — the same text {@link #broadcast} sends. */
    public record Message(String html, String buttonText, String buttonUrl) {
    }

    /**
     * Posts the day's {@code kind} message to every configured chat that has not had it yet.
     * {@code day} is the UTC day whose round is announced; {@code now} is only read for the hours
     * left in the evening message.
     */
    public List<ChatResult> broadcast(TelegramBroadcast.Kind kind, LocalDate day, Instant now) {
        if (!enabled()) return List.of();

        Message message;
        try {
            message = compose(kind, day, now);
        } catch (RuntimeException e) {
            // selectDailyRound refuses when no pair has history: no message, and say so.
            recentErrors.record("telegram", kind.name(), "Không soạn được tin: " + e.getMessage());
            log.error("Could not compose the {} Telegram message for {}", kind, day, e);
            return chatIds.stream().map(id -> new ChatResult(id, Outcome.FAILED, e.getMessage())).toList();
        }

        List<ChatResult> results = new ArrayList<>(chatIds.size());
        for (long chatId : chatIds) {
            results.add(sendOnce(chatId, kind, day, now, message));
        }
        return List.copyOf(results);
    }

    private ChatResult sendOnce(long chatId, TelegramBroadcast.Kind kind, LocalDate day, Instant now, Message message) {
        // Checked first, inserted second: an insert that fails its constraint inside a caller's
        // transaction would leave that transaction unusable. The catch is for the real race.
        if (broadcasts.existsByChatIdAndKindAndDay(chatId, kind, day)) {
            return new ChatResult(chatId, Outcome.ALREADY_SENT, null);
        }
        TelegramBroadcast claim;
        try {
            claim = broadcasts.saveAndFlush(new TelegramBroadcast(chatId, kind, day, now));
        } catch (DataIntegrityViolationException e) {
            return new ChatResult(chatId, Outcome.ALREADY_SENT, null);
        }

        try {
            bot.sendMessage(chatId, message.html(), message.buttonText(), message.buttonUrl());
            log.info("Posted the {} message for {} to Telegram chat {}", kind, day, chatId);
            return new ChatResult(chatId, Outcome.SENT, null);
        } catch (RuntimeException e) {
            broadcasts.delete(claim);
            String reason = e instanceof TelegramBotClient.TelegramApiException ? e.getMessage()
                    : e.getClass().getSimpleName();
            recentErrors.record("telegram", String.valueOf(chatId), reason);
            log.warn("Telegram {} message to chat {} failed: {}", kind, chatId, reason);
            return new ChatResult(chatId, Outcome.FAILED, reason);
        }
    }

    public Message compose(TelegramBroadcast.Kind kind, LocalDate day, Instant now) {
        long number = DailyRound.forDay(day).number();
        String play = appLink + "?startapp=daily";
        return switch (kind) {
            case MORNING -> new Message(
                    "☀️ <b>Thử thách #" + number + "</b> đã mở\n"
                            + "Cả nhóm cùng một chart: " + properties.round().guessesPerChart()
                            + " lần đoán nến lên hay xuống, mỗi người một lượt.",
                    "Chơi thử thách #" + number, play);
            case EVENING -> evening(day, now, number, play);
        };
    }

    private Message evening(LocalDate day, Instant now, long number, String play) {
        RoundSelection round = rounds.selectDailyRound(day);
        int total = properties.round().guessesPerChart();
        List<Object[]> finishers = guessResults.roundFinishers(GuessMode.DAILY, round.asset().getId(),
                round.timeframe(), round.startIndex(), total);

        long hoursLeft = Math.max(0, Duration.between(now,
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).toHours());
        String left = hoursLeft >= 1 ? "Còn " + hoursLeft + " tiếng" : "Sắp hết giờ";

        if (finishers.isEmpty()) {
            return new Message(
                    "🕗 Chưa ai chơi xong <b>Thử thách #" + number + "</b> hôm nay. " + left + ", ai mở hàng?",
                    "Chơi ngay", play);
        }

        StringBuilder text = new StringBuilder("🏁 <b>Thử thách #").append(number).append("</b>: bảng tạm tính\n");
        for (int i = 0; i < Math.min(TOP, finishers.size()); i++) {
            Object[] row = finishers.get(i);
            User user = (User) row[0];
            long correct = ((Number) row[1]).longValue();
            text.append(MEDALS[i]).append(' ').append(escape(user.getDisplayName()))
                    .append(": ").append(correct).append('/').append(total).append('\n');
        }
        text.append(finishers.size()).append(" người đã chơi xong. ").append(left).append(" để vào bảng.");
        return new Message(text.toString(), "Vào chơi", play);
    }

    /** Telegram's HTML mode understands only these three; a display name is somebody's own text. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Comma-separated chat ids, as {@code getUpdates} reports them — a group's is negative, and a
     * supergroup's starts {@code -100}. Anything that is not a number is dropped rather than
     * failing startup, and logged, since a reminder is not worth the site not booting.
     */
    static List<Long> parseChatIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String part : Arrays.stream(raw.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList()) {
            try {
                ids.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                log.warn("Ignoring a Telegram chat id that is not a number: {}", part);
            }
        }
        return List.copyOf(ids.stream().distinct().toList());
    }
}
