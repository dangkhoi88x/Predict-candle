# Live round

The shared call on the candle the exchange is building now, its history popup, and the admin pane that can delete a round's calls.

## Live round

A second game shape next to practice: not a random historical chart, but one shared call on
whichever candle the exchange is building this second — the kind of thing rekto.fun's
next-candle mini-game does. `GET /api/live/round` and `GET /api/live/history` are public;
`POST /api/live/predict` needs a wallet, enforced in `SecurityConfig` the same way
`/api/stats/**` is.

`LiveRoundService` reads the wall clock through an injected `Clock` bean (`ClockConfig`), not
`Instant.now()` directly — a test can pin it to an instant known to be inside a round's open
window. This is not a style preference: `LiveRoundFlowTest` used to read `Instant.now()`
independently of the service, which agreed right up until a build happened to run in the ~8
minutes of every hour a round is locked, and a predict() call the test expected to succeed came
back 400. It reached `main` this way before anyone noticed the pattern.

**Nothing stores a round.** `LiveRound.at(now, timeframe, lockBefore)` names the round from the
clock alone — a round *is* the real candle open at that instant — so two servers, a page reload
and a player who joins mid-round all agree on which round is running without a token. Picks lock
`candles.live.lock-before` (default 8 minutes on the 1h timeframe) before the candle closes;
without that gap a player could watch the price and call the obvious.

The live price is `PriceDataProvider.fetchCandles` asked for exactly the candle
`CandleSyncService` deliberately never stores — that service stops one millisecond short of
"now" so it never freezes a still-moving candle, and the live round asks for exactly that one,
cached `candles.live.price-cache-ttl` (2s) so concurrent viewers share one upstream call. Once a
round has closed, the settled row is authoritative and cheaper, so the exchange is only asked
while a round is still open.

**A round is labelled by when its candle opened, not by its number** — "Vòng 21:00", plus the
date once it is not today (`CandleFormat.roundLabel`), on the live tab, the history strip, the
popup and the topbar banner. `roundNumber` counts hours since 1970 and stays the id everything is
addressed by (the popup's fetch, the admin pane, its tooltip); "#23705" was simply never something
a player could place.

An empty pool is drawn as a neutral bar reading "Chưa ai dự đoán" (`drawPool`, shared with the
history popup), not the 50/50 split an empty pool used to show — that read as two players having
called it opposite ways. With calls, each side shows its count beside its share.

`live_predictions` carries the same integrity story as `guess_results`: one row per (user,
asset, timeframe, open_time), a unique constraint rather than a check the application could
forget. Recording checks first and inserts second — an insert that fails its constraint leaves
the persistence context needing a rollback, so a caller reusing that context (any request inside
one transaction) finds unrelated later queries broken by an exception a different request
already recovered from.

`GET /api/live/history/{roundNumber}` replays one settled round for the history strip's
detail popup: the candle it closed on, plus `candles.live.context-candles` either side (default
20) so the popup draws the same run-up and aftermath a player watching live would have seen.
`LiveRound.byNumber` is the inverse of `LiveRound.at` — going from a round number a player
clicked on back to its `openTime`, pinned by a round-trip test over 50 rounds. Reading the
in-progress round's own detail is refused (400): that round has no settled candle yet, and
`GET /api/live/round` already covers it.

**Chart labels are counter-scaled so they stay readable** (`CandleChart.fitLabels`, run at the
end of every `draw`, `drawIndicator` and crosshair move, and on resize). With
`preserveAspectRatio="none"` text stretched with the candles, and a 1000-unit practice chart in a
360px phone squeezed every label to a third of its width. Each `text` is scaled around its anchor
so it renders at its font size in CSS pixels, never smaller, with natural proportions; a tag's
background (`rect` just before it, `data-label-bg` or `data-part="*-bg"`) gets the same transform,
and anything pushed past the edge is nudged back in. Because labels no longer shrink with the chart,
**a tag must be sized from its text, not from the padding** — a badge as wide as the price column
gets scaled up with its text and spans the chart. `options.padRight` (viewBox units) lets a caller
keep ~62px for the price column on a narrow box; `app.js`'s `roomFor` computes it.

The practice chart's time axis reads **hours back from the newest candle** ("−19h … 0h"). It used
to print clock times counted back from *now*, so a chart from last winter read as today's trading
beside a live ticker tens of thousands of dollars away; the candles carry no timestamp on purpose,
and an invented one is worse than none.

Frontend draws that candle context with `candle-chart.js`
(`window.CandleChart.draw(svg, candles, options)`), which every candlestick chart on the site
now goes through — `app.js` used to carry two more renderers of its own and they have been
folded in.
`live.js` clicks a round in the history strip, fetches its detail, and opens a popup: a mini
candlestick chart with a dashed line at the round's open price, a trophy badge naming the
winner, and the open/close prices and pool split. Closing follows the modal's own three exits
(✕ button, backdrop click, Esc) rather than nav.js's tab machinery, since this sits above the
tab it opened from rather than being one.

Frontend is deliberately not a live-updating candlestick chart. A big rolling price, a sparkline
of the last `candles.live.history-size` closes, a lock/close countdown reusing `.guess-timer`,
and a pool-split bar are what `live.js` renders — same visual language as practice
(`.guess-btn.long/.short`, `--up`/`--down`), built on first reveal like heatmap and blog.

The round response carries a `participants` roster — up to 50, newest call first — alongside
the pool split: display name and direction, never a wallet address. `findParticipants` joins
fetch on `user` for exactly this, since every row is about to read `getDisplayName()` and
without the join that's N+1 queries on every poll. Same rule the leaderboard already holds to:
a display name defaults to a shortened wallet (`AuthService.shortAddress`), and that is the
thing shown to a page nobody had to sign in to open — never the raw 42-character address. Each
row also carries `walletShort` (`User.getShortWalletAddress`) separately from the display name:
an un-renamed account's name already is that shorthand, so the frontend only draws it as a
second line when an admin has renamed the account and the two have diverged — the "Raccon" /
"0xef00…4d45" pairing rekto.fun's own roster shows. The avatar is an emoji plus a background
color, both chosen by hashing `walletShort` rather than the display name, so a renamed account
keeps the same avatar it always had.

**The admin's copy of the live round is a separate pane, and the reason it exists is the one
thing that cannot be fixed from anywhere else.** A live result is not stored: it is recomputed
from the exchange's candle on every read, by the same join `SETTLED_LIVE_FLAGS` makes. So a
round that settled on a bad price has no wrong answer written down to correct — the wrong
answer is derived fresh each time anyone asks. The only thing that can change is whether the
calls exist, which is why `DELETE /api/admin/live/rounds/{n}` is a delete and not a `voided`
flag: five separate readers already fold `live_predictions` (score, the leaderboard, retention,
the day streak, the ops panel), and a flag would have to be remembered in every one of them and
in every query written afterwards. A row that is gone cannot be forgotten. What that costs is
stated in the confirmation the pane shows: the calls leave no trace, so a player whose only
play that day was this round loses the day off their streak with it.

`roundsWithCalls` drives the list from `live_predictions` rather than from `candles` — there is
a round every hour whether or not anyone was looking, and a round belongs on an admin's list
exactly when there is something on it to manage. Its left join is the point of the page: a
round whose candle never arrived still shows its calls and reads "thiếu nến", and those calls
score nothing anywhere until the gap in candle history is filled.

The admin roster carries the wallet address and the account id; the public one deliberately
never does. Same rule, opposite side of it: a display name defaults to a shortened wallet so a
page nobody signed in to open cannot be scraped for addresses, and an admin who opened a round
did so to find an account.

**The challenge preview is the one admin page that answers a question nobody could ask before.**
The daily chart and the pattern quiz are both functions of the date and neither stores anything,
so there is no row to inspect and no midnight job whose log says what tomorrow will be. Both
selectors can also *refuse*: `selectDailyRound` throws when no pair has enough history,
`PatternQuizService` throws when no pattern in the library has a single unambiguous occurrence
anywhere. Either one breaks a whole day of the site for everybody until the next midnight, and
neither said a word in advance. `AdminChallengeService` calls them early and turns the exception
into a message on a row.

Three things about it are load-bearing:

- **A future day is provisional and today is not.** A day's chart is drawn from the candles that
  had closed before *its* midnight; for today that count is frozen (which is what
  `anHourlySyncDoesNotMoveTodaysChart` pins), and for a day still to come it is still rising, so
  the pick moves as the sync lands. A future row proves the day can be built, not what it will
  be, and it is labelled that way.
- **A past day's pattern is read back, not rebuilt.** Every `pattern_quiz_results` row for a day
  carries the pattern it asked about — that is the question, not the answer — so `askedPatternIds`
  is one indexed read instead of a scan of every asset's history against every pattern. A past day
  nobody answered has neither, and says so.
- **The daily's candles carry their timestamps here, and the answer directions come with them.**
  The player's copy deliberately has neither, because for that game a date *is* the answer. This
  is the one screen where reading the date is the point, and "is tomorrow's chart a coin flip" is
  not a question that can be asked without the answers.

`candle-chart.js` is loaded on demand rather than from a `<script>` tag, the same bargain
`admin-blog.js` makes with the Tiptap bundle: 28 KB that only an admin who opens a day's detail
ever needs, on the page whose weight this project spent a release cutting.
