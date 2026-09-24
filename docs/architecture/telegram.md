# Telegram

The Mini App, Telegram sign-in, and the bot's daily reminders in groups.

## Telegram Mini App

The same `index.html`, opened inside Telegram. `telegram.js` does nothing unless the URL carries
Telegram's launch parameters (`#tgWebAppData=…`, `?tgWebAppStartParam=…`), and only then fetches
Telegram's script — a browser visitor pays for none of it. Inside, it signs in, follows the
`startapp` parameter (`thach_<id>`, `daily`, `live`) and routes sharing through Telegram's share
sheet via `CandleTelegram.share(text, path)`; `app.js` and `daily.js` try that first and fall back to
the web share sheet or the clipboard.

**Sign-in is `POST /api/auth/telegram {initData}`, verified by `TelegramInitDataVerifier`** the way
Telegram documents it: HMAC-SHA256 of the sorted `key=value` lines, keyed with
HMAC-SHA256("WebAppData", bot token), compared in constant time, and `auth_date` no older than
`candles.telegram.init-data-max-age` (24h) — initData is the same string for a whole launch, and a
copied one should not be a login forever. Without `TELEGRAM_BOT_TOKEN` the endpoint is a 404 and
`/api/site-config` carries no `telegram`, so a deployment without a bot advertises nothing.

**A Telegram account is a `users` row keyed `tg:<telegram id>` in `wallet_address`**, with no
migration. That column is the account's identity and unique; making it nullable for a second kind
of account would touch every reader that assumes a value. The prefix also means no Telegram account
can ever equal an address in `candles.admin.wallets` — **admin stays a wallet's role**. A Telegram
account and a wallet are two separate accounts; linking them is not built. The display name is
`@username`, else the first and last name, taken at first sign-in and never overwritten.

`telegram.js` waits for `CandleAuth.whenRestored()` and signs in only when that found no session: a
refresh cookie naming an account wins over a launch naming another. Clicking the name does not
load the wallet bundle for a `tg:` account (`CandleAuth.isTelegramUser`).

**Framing.** Telegram Web runs a Mini App in an iframe, and Spring Security's default
`X-Frame-Options: DENY` gave it a blank panel. That header has no allow-list, so it is off and
`Content-Security-Policy: frame-ancestors 'self' https://web.telegram.org` carries the same
protection with one exception; `TelegramLoginFlowTest` pins both headers.

Setting up the bot (BotFather, `/newapp`, the three variables) is `docs/DEPLOY_PLAN.md` §4.4.

## Telegram group reminders

The bot posts the daily into the groups that asked for it: at 08:00 Vietnam time that today's
round is open, at 21:00 the top three so far and the hours left (`TelegramDailyScheduler`, crons
`TELEGRAM_MORNING_CRON` / `TELEGRAM_EVENING_CRON`, `-` switches one off).
`TelegramDailyBroadcastService` composes and sends; `TelegramBotClient` is the Bot API.

- **Opt-in by chat id** (`TELEGRAM_DAILY_CHAT_IDS`). Adding the bot to a group sends nothing;
  the list is where a group's agreement is written down. Also off without the bot's username and
  app name, since the button opens `t.me/<bot>/<app>?startapp=daily`.
- **No message gives the chart away** — the share text's rule: round number and scores, never the
  pair or a date. `theMorningMessageGoesToEveryChatOnceAndGivesNothingAway` checks the symbol.
- **Each message is claimed before it is sent** (`telegram_broadcasts`, unique on chat, kind,
  day). Render keeps the old instance running through a deploy, so two schedulers can fire the
  same minute; the claim makes that one post. Insert-then-send means a crash in between loses a
  reminder rather than doubling one. A failed send deletes its claim and goes to recent errors.
- **The standings are `roundFinishers`**: DAILY rows on that day's chart, finished only, admins
  out (the leaderboard's rule), best score then earliest finish. They are a snapshot — the round
  runs to UTC midnight, 07:00 in Vietnam — so the message says hours left, not winners.
- **Display names are escaped** for Telegram's HTML mode (`&`, `<`, `>` only — `HtmlUtils` would
  turn Vietnamese letters into named entities Telegram does not know).
- **The bot token is in every Bot API path**, and Spring's I/O exceptions quote the URL. The
  client rethrows every failure as `TelegramApiException` built from Telegram's `description` or
  the exception type, never the original message, so the token cannot reach the log or the ops
  pane. It is also concatenated into the path, not a URI variable: a variable percent-encodes the
  token's colon.
- A job that fires while Render has the instance asleep does not run; the keep-awake cron
  (DEPLOY_PLAN §4.3) is what makes the times reliable.

**The admin card** sits on the challenges pane (`admin-telegram.js`, `GET /api/admin/telegram`,
`/chats`, `POST /send?kind=`). It previews both of today's messages from the server's own text,
drawn with `createElement` since a display name is inside it; lists what has been claimed today;
finds a group's chat id through `getUpdates` (no offset, so reading acknowledges nothing — and it
only works because this app never sets a webhook); and sends a message now through the same
claims, so "send now" *is* that day's message and the schedule then skips it. It cannot change
where the bot posts: that stays `TELEGRAM_DAILY_CHAT_IDS`, for the reason roles stay in config.
