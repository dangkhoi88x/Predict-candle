# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
docker compose up -d          # Postgres on localhost:5544 (5432/5433 avoided on purpose)
./mvnw spring-boot:run        # app + frontend on http://localhost:8080
./mvnw test                   # all tests
./mvnw test -Dtest=WalletSignatureVerifierTest        # one class
./mvnw test -Dtest=WalletSignatureVerifierTest#method # one method
```

First run backfills ~40k candles per asset from Binance (2022-01-01 → now), 15–30s. Watch for
`Synced N candles for ...` in the log.

**Editing static files while the app runs:** `./mvnw -q process-resources` copies
`src/main/resources/` into `target/classes/`, which is where Spring serves static assets from.
The running server picks the change up on the next request — no restart. This does **not**
apply to `application.yaml` or Java changes; those need a real restart.

**Bundles:** `cd web && npm ci && npm run build` rebuilds both, each as an IIFE assigning one
global. Only needed when the matching source changes. Use `npm ci`, not `npm install` —
`package.json` uses carets, so an install can silently pull newer minors and produce a
different bundle from an unchanged source.

| source | output | global | loaded by |
|---|---|---|---|
| `web/src/wallet-auth.js` | `static/wallet-auth.js` (4.1 MB) | `CandleWallet` | both pages, injected by `auth.js` on the first connect click — **never a `<script>` tag**: admin.html had one, and it made 1.1 MB of the admin page's 1.4 MB transfer, on every visit including the ones that only read the ops panel |
| `web/src/blog-editor.js` | `static/blog-editor.js` (395 KB) | `CandleEditor` | injected by `admin-blog.js` when the blog pane is revealed, and awaited before the editor opens — **no `<script>` tag** |

IIFE takes exactly one entry, so these cannot be one multi-entry build — `vite.config.js`
switches on `--mode` and `npm run build` runs both.

`package.json` carries an `overrides` pin on **axios**. `@coinbase/cdp-sdk`, four levels down
under the Reown wallet adapter, depends on axios at an exact `1.16.0` — which npm cannot
upgrade past ten advisories, so `npm audit fix` loops and even `--force` proposes nothing. The
override is the only route, and it is safe because that path is tree-shaken out: bumping axios
to 1.20.0 leaves `wallet-auth.js` byte-identical. Do not delete it without re-running
`npm audit`.

## Architecture

### Package layout

Layered, not by feature: `controller/ service/ repository/ entity/ dto/`, plus five supporting
packages where a layer name would lie about the contents.

| package | holds |
|---|---|
| `controller/` | the 20 `@RestController`s |
| `service/` | the 23 `@Service`s, plus `RateLimiter` and `CandleSyncScheduler` |
| `repository/` | the 6 Spring Data interfaces |
| `entity/` | the 6 `@Entity` classes and the 5 persisted enums (`GuessMode` is PRACTICE / DAILY / ARCHIVE) |
| `dto/request/` | the 5 records a client sends in: `GuessRequest`, `WalletVerifyRequest`, `BlogPostRequest`, `ContentItemRequest`, `LegacyStatsRequest` |
| `dto/response/` | the 17 records the server sends out, including the pieces nested inside them (`CandleDto`, `BlogPostDto`, `PlayerSummary`) |
| `domain/` | internal value records that never leave the server: `RoundToken`, `RoundSelection`, `AuthSession`, `PlayerScore`, `PlayStreak`, `DailySeed`, `DailyRound`, `HintLevel`, `Achievement`, `StoredMedia` |
| `security/` | `JwtService`, the filter, `WalletSignatureVerifier`, `AdminAccess`, `AdminWallets`, `AdminRoleReconciler` |
| `client/` | Binance and Yahoo, their DTOs, and `Timeframes` |
| `pattern/` | the two pattern libraries and their matchers — algorithm, not a layer |
| `config/` | `@Configuration`, `@ConfigurationProperties`, the rate-limit interceptor |
| `exception/` | the 5 exceptions and `GlobalExceptionHandler` |

`dto/` is the boundary, not a dumping ground for records: `RoundToken` is signed into a JWT and
`AuthSession` carries a refresh token, so neither belongs there even though both are records.

The request/response split is by **direction of travel**, not by name. `CandleDto` and
`BlogPostDto` carry no `Response` suffix but only ever travel outward, so they are responses;
nothing in `dto/request/` is ever returned. A record that had to go both ways would be the
signal to stop and split it in two, because a field that is optional coming in and guaranteed
going out cannot be the same field.

**Tests live in the package of what they test**, which is what lets `AssetSeedOrderTest` and
`CloudinaryUrlTest` reach package-private members. Moving a test's subject means moving the test.

The layer split cost `pattern/` some encapsulation: `CandleHistoryLoader`, `SwingPivotDetector`,
`SwingPoint`, `TechnicalPatternDefinition`, `TechnicalPatternLibrary` and
`TechnicalPatternMatcher` were package-private and had to open up once the three services that
use them moved to `service/`. Nothing outside those services should call them.

### Round flow (the game)

```
Binance → CandleSyncService (backfill + hourly) → Postgres
                                                     │
                            RoundSelectionService — random window, rejects repeats
                                                     │      and near-flat "dead" charts
                            RoundTokenService — signs a JWT holding the answer
                                                     │
        GET /api/practice/round  ·  POST /api/practice/guess
```

The server keeps **no round state**. `roundToken` is a signed JWT carrying asset, window start
and which guess the player is on; the client sends it back with each guess. It also carries an
`iatMs` claim, and **timing is measured from that, never from `iat`** — a JWT's `iat` is a
NumericDate, so it rounds down to the second and a token minted at `.900` makes an instant
answer look 900ms old. That is what `min-think-time` is checked against, so reading `iat` let
roughly two automated answers in three through the floor meant to stop them. One chart yields
several guesses (`candles.round.guesses-per-chart`), each revealing one more candle.

### Auth

Wallet-signature login, no passwords. `GET /wallet/nonce` → client signs it → `POST
/wallet/verify` → server recovers the address and issues a session.

Access token lives **in memory only** (never localStorage); the refresh token is an HttpOnly
cookie, so `auth.js` silently POSTs `/api/auth/refresh` on load to restore a session.
`JwtAuthenticationFilter` authenticates the request as a bare `Long` principal (much of the
codebase pattern-matches on that) plus one authority, `ROLE_USER` or `ROLE_ADMIN`.

Since anyone can connect a wallet, **`.authenticated()` alone means "everyone"** on this app.
Anything narrower uses the role.

### Roles

Two: `USER` and `ADMIN`, on `users.role`. Who is an admin comes from `candles.admin.wallets`
(env `ADMIN_WALLETS`), not from the database — `AdminRoleReconciler` promotes listed wallets
and **demotes** unlisted admins at every startup, and `AuthService` promotes at login for a
listed wallet that has never signed in. So an admin screen cannot grant the role; editing the
config and restarting is the only way, which is deliberate (revoking has to be as easy as
granting). Change that class if that trade stops being worth it.

The role travels in the access token, so `hasRole(...)` in `SecurityConfig` costs no query.
That claim is a snapshot up to 15 minutes stale, so **anything that writes calls
`AdminAccess.requireAdmin()`**, which re-reads the role from the database. `User.assignRole`
also bumps `tokenVersion`, killing the account's refresh tokens on any role change.

### Frontend

Plain static files under `src/main/resources/static/` — no bundler, no framework, ES5-style
IIFEs, one global per file. All six views live in **one `index.html`**; `nav.js` toggles
`.hidden` between them and drives a `role="tablist"` (roving tabindex, arrow keys).

Script order in `index.html` matters: `pill.js` and `rolling.js` define shared globals that
later files call at load time.

| Shared module | Global | Used by |
|---|---|---|
| `pill.js` | `CandlePill.attach(track, sel)` | nav + 6 asset/filter pickers |
| `rolling.js` | `CandleRolling.update(el, text)` | price, delta, scoreboard, ticker, heatmap |

`nav.js` fires `candles:view` (`detail.view`) on every switch, mirroring `candles:pane` on the
admin page. The game listens for it: **auto-advance stops dealing charts when nobody is
watching** — the browser tab backgrounded, or the game view switched away from inside the app.
The countdown itself stays wall-clock and a round already on screen still expires and is still
recorded, because that is what stops a player parking a round and going to look the chart up.
What stops is the manufacture of rounds nobody saw: an unattended tab used to bank roughly 170
recorded misses an hour.

`CandlePill` watches the `active` class via MutationObserver rather than clicks, so callers
keep their own click handlers unchanged and only add one `attach()` line.

**Deferred tabs:** `nav.js` has an `onFirstShow` map. Heatmap, blog, patterns, technical
patterns and psychology all build on first reveal, not at load — together they were 1863 of
the 5494 elements on the page, a third of the DOM built for tabs most visitors never open, and
`view-technical` alone was 1423, four times the game view the player is actually looking at. Add to it rather than initialising a heavy tab eagerly — `loading="lazy"` does
**not** defer images inside a `display:none` view (an element with no box cannot be deferred
by position), so anything image-heavy must be built on demand.

**Content comes from the API only.** `blog.js`, `patterns.js`, `technical-patterns.js` and
`psychology.js` each used to carry the array they were seeded from and serve it when a request
failed. Those are gone — a failure now draws `.view-notice` through `CandleContent.notice`,
because stale content presented as current is a worse answer than an honest empty state.
`CandleContent.load(kind)` throws rather than returning a fallback.

Two consequences worth knowing. `blog.js` builds on first reveal, so its catch clears `built`
— otherwise one dropped request leaves the tab empty for the whole visit with no way to ask
again. And `CandlePatterns.nameOf`, which the game tab calls to name a pattern found mid-round,
now reads what the fetch returned instead of the deleted array; it still falls back to the raw
id, which also covers being asked before the fetch lands.

### Admin frontend

`admin.html` is a second, separate page: a dashboard shell — sidebar, sticky topbar, and seven
panes of which exactly one shows. It shares `style.css`, `theme.js` and `auth.js` with the game
and nothing else.

**Pane switching is an attribute, never `.hidden`.** Every `admin-*.js` module already owns
`.hidden` on its own section and re-asserts it each time it hears `candles:admin` — so a nav
writing the same class would lose the pane the moment a module refreshed (press Sync in Vận
hành and watch it jump back). `admin-nav.js` sets `data-pane` on `.admin-panes` instead, and
CSS shows a section only when the container selects its pane **and** its module has not hidden
it. `body.is-admin`, set once by `admin.js`, is what hides the nav and panes before the server
has confirmed the role.

| Event | Fired by | Carries |
|---|---|---|
| `candles:admin` | `admin.js` | whether `/api/admin/me` said yes; every module loads off this |
| `candles:ops` | `admin-ops.js` | the ops snapshot, so the overview pane reuses it instead of fetching again |
| `candles:pane` | `admin-nav.js` | the pane just switched to |

`CandleAdminNav.go(pane)` is the way to move between panes from code — `admin-media.js` uses it
to take the blog editor's image picker to the library and back.

Overview charts come from `GET /api/admin/stats?range=week|month|year` (`AdminStatsService`,
cached 60s, bucketed in UTC; `&fresh=true` is the refresh button skipping that cache). The four
KPI figures do **not**: they come off the `candles:ops` snapshot, because two panes asking the
same question twice can only disagree.

**Two guess totals, and picking the wrong one is a visible bug.** A timed-out guess has no
`guessed_direction`, so each `AdminStats.Bucket` carries both `guesses` (every row — the
denominator `PlayerScore` and the ops panel already score on) and `answered` (long + short, the
chart column's height, because the legend says SHORT and LONG). Accuracy anywhere on the page
is `correct / guesses`; reading `correct / answered` instead runs about nine points high on
current data. `AdminStatsTest` pins both the split and the JSON field names the pane reads —
there is no shared schema, so a renamed record component would silently draw zeroes.

The blog body is **Tiptap in the admin, a ProseMirror document in `body`, and a hand-written
walker on the public page**. That split is the load-bearing decision:

- `admin-blog.js` holds only the handle `web/src/blog-editor.js` returns. It never imports
  Tiptap and never sees a ProseMirror object beyond the JSON going into the column.
- `blog-render.js` (7 KB) draws the same documents on the public page with `createElement`.
  Rendering them with Tiptap's own extensions would put 395 KB of editor in front of every
  reader, on the page whose weight this project spent a release cutting. It also means
  `blog.js` still never touches `innerHTML`.

**The cost is a coupling: every node or mark the editor can emit needs a branch in
`blog-render.js`.** Add a Tiptap extension without one and the public page cannot draw what
an admin just published. Unknown types fall back to their text and `console.warn` rather than
vanishing silently.

Two other things are deliberate. `Image` is a custom node carrying `width`/`height`, because
the public page reserves an image's box from them and stock Tiptap Image drops them — which is
also why `POST /api/media/images` returns dimensions, so a *pasted* image gets the same
treatment as a picked one. And an `href` is validated in both places: Tiptap refuses anything
but http/https, and `blog-render.js` checks again on the way out, because the editor is a
convenience and not the security boundary.

`body` still reads both shapes. V12 converted the seeded posts to documents, but a database
that has not run it yet holds the older flat block array, and opening one of those must not
present an empty editor that then saves over the post.

The topbar search (`admin-search.js`) searches the **rendered DOM**, not the modules' data —
every pane is built and in the document at once, only hidden by CSS, so the rows are all there
for free and no module has to expose its state. The index is therefore exactly what has
loaded: the content pane holds one kind at a time and the media grid holds the pages fetched
so far, and the empty state says so. Matching folds diacritics and collapses `- _ / .` to
spaces (so `van hanh` finds `Vận hành` and `cau truc` finds `cau-truc-thi-truong`), and reads
`title` attributes, which is where the full wallet address and Cloudinary id live while the
cell shows an abbreviation. Teaching it about a new pane is one entry in `SOURCES`.

The admin page has its own icon set (`admin-favicon.*`) and its own `theme-color`, on the
admin ground rather than the game's. `theme.js` reads those colours off the meta tags instead
of holding a table, so it does not need to know which page it is running on.

Admin styling lives under `.admin-shell` and reads `--adm-*` tokens, a palette of its own —
soft grey ground, hairline borders, low shadow, against the game's near-black. The scope is
load-bearing: `.ghost-btn`, `.pill`, `.status` and `.field` are shared class names, and only
the `.admin-shell` prefix keeps the two pages from having to agree on how they look.

### Live round

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

Frontend draws that candle context with a new shared module, `candle-chart.js`
(`window.CandleChart.draw(svg, candles, options)`), rather than the practice tab's own SVG
renderer in `app.js` — those closures aren't exported on `window`, and reusing them properly
would mean pulling them into a shared module first, which is a separate change from this one.
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

### Daily archive

Past daily rounds are replayable — `GET /api/daily/archive`, `GET /api/daily/archive/{day}`,
`POST /api/daily/archive/{day}/guess`. One attempt per archived day, from the same unique
constraint the daily uses; the window is the last 60 days (`MAX_ARCHIVE_DAYS`), the list defaults
to 14.

**A replay is recorded as `ARCHIVE`, never `DAILY`, and that separation is the feature rather
than bookkeeping.** `distinctDailyDaysDesc` counts days holding a DAILY row, so if a replay were
written as DAILY it would stamp *today* as "played the daily" without today's chart ever being
opened — a streak could then be held indefinitely by working through the archive, which is the
same as not having a streak, and every badge and share card resting on it would mean nothing.
`DailyArchiveFlowTest.archivePlayDoesNotKeepTheDailyStreakAlive` pins exactly that, and it does
fail if the mode filter is removed from the streak query.

Today is **not** archivable (400). Otherwise the archive would be a second door to the same
round, writing rows the streak cannot see.

The day travels in the path, not in the token — the token carries only a chart — so
`checkIsArchived` checks the pair against each other. Without it a token minted for one archived
day could be spent on another.

A replay returns a null `streak` and a null `nextRoundAt`: it moves neither, and claiming either
would be a lie the client would draw. Opening the tab always lands on today, never on whichever
day was last replayed.

Replays do count toward score, the leaderboard and badges, the same as practice — they are real
calls on real charts, and capped at one attempt per day they are less grindable than practice
already is.

One rare edge, deliberately left alone: two different days can pick the same (asset, startIndex)
by coincidence, and the unique constraint would then treat them as one archived round. With tens
of thousands of windows per asset this is remote, and pinning it would mean carrying the day on
`guess_results` for no other reason.

### Achievements

Nine badges on the profile, from `Achievement` — a pure enum where each entry is a name, a
threshold and which number it reads off a `Snapshot`. Sent inside `/api/stats/me`, since
`StatsService` already computes almost every input; the daily streak is the one extra query.

**Nothing is stored, and that was a deliberate choice against the obvious design.** A badge is
not a row written when a player crosses a line, it is a question asked of their history — the
same shape as `PlayStreak` and the daily's attempt state. What that buys:

- Badges cannot drift from what a player actually did: no write path to forget, no award that
  fires twice, and deleting an account's results takes its trophies with them.
- A badge added later is awarded retroactively to everyone who already qualified, instead of
  needing a backfill migration per badge.
- Thresholds can be tuned. With a table, lowering one leaves already-qualified players unbadged
  until they play again; raising one leaves badges standing on accounts that no longer qualify.

What it gives up is real and worth knowing before extending this: **there is no *when***. The
profile cannot say "earned on March 3rd", and the server cannot tell that a badge is new this
request. If that is ever needed, add a table of first-earned timestamps *beside* `Achievement`,
never instead of it — the rule stays in the enum.

Badges read `recorded` totals only, never the `legacy_*` carry-over — same reason the
leaderboard refuses them: those numbers are client-supplied, so counting them would make posting
a large believable number the fastest route to a wall of trophies.

`Achievement.Progress` is a `domain/` record and does not leave the server; `StatsResponse.Badge`
is the copy that does. Unearned badges are returned too, with progress — a badge nobody can see
themselves approaching is not a goal, and goals in reach are the thing that still works when a
streak breaks. The profile sorts earned first, then unearned by how close they are.

### Progressive hints

The chart gives ground as a player misses on it: 1 miss unlocks volume, 2 the 5-candle moving
average, 3 the name of a candlestick pattern in what is already on screen (`HintLevel`). Both
games get it — practice and daily share `RoundPlayService`.

Keyed to **misses, not guess number**. A player reading the chart correctly is handed nothing;
one who is lost gets more each time. Missing on purpose to unlock a hint is possible and costs
the guess, the streak and the point — the same trade Songless makes with its skip button, not a
loophole.

`misses` rides in the signed round token, because the server keeps no session and a count the
client could edit would be a difficulty dial the client owns. The daily resumes from the
recorded rows instead: a player who closed the tab has no token left, so the guesses are the
only thing that survived.

**No hint may reach past the last revealed candle** — `RoundHintService` reads a window ending
there, so a hint is always a second look at what is on screen rather than a peek at the answer.
`ProgressiveHintFlowTest` pins that with a fixture that alternates direction, which is what lets
it choose to be right or wrong on demand and drive the miss count exactly rather than climbing
the ladder by luck.

Two frontend traps, both hit while building this:

- **Set `chart.hints` before the reveal animation, not after.** The animation is what redraws
  `app.js`'s chart, so assigning afterwards left every hint a guess late — named in the text,
  invisible on the chart until the next candle. The hint series are sized for the chart
  *including* the candle about to appear, and both renderers ignore entries past the candles
  they have.
- **The volume strip comes out of the price plot**, in both `app.js` and `candle-chart.js`. The
  viewBox is fixed by the caller, so candles make room rather than the chart growing; the strip
  is only reserved once volume is actually unlocked, leaving an unhinted chart exactly as it was.

Volume scales to the tallest bar in its own strip — it shares no units with the price axis
beside it. The average draws in `--accent`, not up/down colours: it is a smoothing of closes
already on screen, so it reports no verdict. Its leading entries are null rather than zero,
which is what stops the line diving to the bottom of the chart.

### Daily challenge

One chart a day, the same one for everybody, one attempt. `GET /api/daily/round` and
`POST /api/daily/guess`, both public like `/api/practice`.

**Nothing stores an attempt.** The chart comes from the date (`selectDailyRound`) and the
attempt *is* the recorded guesses: `findRoundGuesses` for today's coordinates rebuilds where a
player got to, so a reload, a second tab or another device all resume the same round, and the
one-attempt rule is the `guess_results` unique constraint rather than a flag anything could
forget to set. That is why V14 put `mode` **inside** that constraint — without it a player who
happened to draw today's window in practice would find their daily already answered.

`mode` is also on the round token, and that part is a security boundary, not a label. Without
it a player could take the daily token, spend it on `/api/practice/guess` to read the answer for
free — a practice row is not a daily attempt — then play the daily already knowing it.
`RoundPlayService` refuses a token whose mode is not the endpoint's, and the daily additionally
refuses a token for a day that has since rolled over.

Practice and daily share `RoundPlayService`: they differ in where the chart comes from and what
may be played, not in what happens when a guess arrives. Two copies of that would eventually
score the same guess two ways.

**The countdown is not optional and is not pausable.** The server refuses an answered guess past
`seconds + grace` measured from when it minted the token, so a daily tab without a visible clock
would silently burn the day's only attempt. It keeps running when the tab is hidden or switched
away from, for the reason the game tab already gives: a pausable clock on a chart you get one go
at is an invitation to park the round and go look the period up.

Two other things follow from anonymous play being allowed. Signed out, **the client must not
re-read the round after finishing** — nothing was recorded, so the server would hand today's
chart back as though it had never been played and wipe the result off the screen, taking the
share card with it. And the daily's candles carry **no timestamps**, like practice: a date is
the answer. `CandleChart` skips its time axis when candles have no time, which it previously
drew as "01/01 08h".

The share text is the round number, the score and the same coloured squares that are on screen —
no asset, no dates. A result you cannot post without spoiling the puzzle is one nobody posts.
`DailyRound.FIRST_DAY` is the origin of the numbering; moving it renumbers every round anyone
has ever shared.

The daily streak (`distinctDailyDaysDesc` folded through `PlayStreak`) counts only days the
challenge itself was played, so it can break while the profile's day streak holds — turning up
to practice is not doing today's chart.

### Retention baseline

`GET /api/admin/retention` (`AdminRetentionService`, cached 60s, `&fresh=true` skips it) exists
to be read **twice** — once before the daily challenge ships and once after. A number only
looked at afterwards cannot say whether the change did anything.

Derived from the timestamps already on `guess_results` and `live_predictions`, like
`PlayStreak`: an events table recording that someone played would be a second copy of data the
app already has, free to drift from it. A cohort is everyone whose *first* recorded call landed
on a UTC day — first play, not sign-up, because an account that never played has not been lost,
it has not started.

Three things here are easy to get wrong, and all three are pinned by `AdminRetentionTest`:

- **Immature cohorts stay out of the denominators.** A cohort that first played yesterday
  cannot have returned within seven days yet. Counted as a cohort that failed to return, every
  new player would push the rate down — the measurement would report losing people exactly when
  the site gained them. Each window has its own denominator (`nextDayEligible`,
  `withinWeekEligible`), both usually smaller than `newPlayers`, and the pane says so in words.
- **Admin accounts are excluded in the SQL**, same reason the leaderboard excludes them: the
  seeded admin plays constantly during development and would read as a player who returns every
  single day.
- **Two return windows, not one.** `returnedNextDay` is the strict next day; `returnedWithinWeek`
  is any of the seven after. At this volume the strict figure is mostly noise, which is why the
  looser one sits beside it rather than replacing it. "D7" on its own is ambiguous enough that
  neither field is named that.

`plays / activePlayerDays` is calls per player per day they played — the denominator counts a
player once per active day on purpose, so the figure does not grow just because the window is
long. The response carries counts and never rates; the pane divides, once, in `renderRetention`.

### Daily round selection

`RoundSelectionService.selectDailyRound(day)` picks the one chart everybody gets on a UTC day,
from the date alone — same asset, same window, on every server and every reload, with nothing
stored. Same reasoning as `LiveRound.at`: a midnight job that generates the day's round has to
run exactly once on exactly one instance, and leaves the site with no round at all if it misses.

Seeding the draw is the easy half of that and **not** the half that breaks. Three things had to
give way, and only the first is obvious:

- `ThreadLocalRandom` becomes `new Random(seed)`. `DailySeed` runs the epoch day through
  splitmix64 first — `java.util.Random` draws visibly related first numbers from adjacent
  seeds, and every day this compares against is adjacent, so unmixed seeds would put
  consecutive days in neighbouring windows of the same asset.
- **The draw's range must not move.** `startIndex` is an offset from the oldest candle, so the
  hourly sync shifts nothing already indexed — but it does widen `count(*)`, and the same seed
  against a range one wider draws a different number. A round picked at 10:00 and the "same"
  round at 11:00 were different charts. `countByAssetAndTimeframeAndOpenTimeLessThan(midnight)`
  holds the range still for the day. `DailyRoundSelectionTest.anHourlySyncDoesNotMoveTodaysChart`
  is the only test that catches this, and it does fail without the frozen count — everything
  else passes either way, which is what makes this worth writing down.
- **The repeat cache is bypassed.** `recentlyServed` is per-instance and expiring; honouring it
  would let a server that already served today's chart hand out a different one. Practice must
  not repeat, daily must.

The asset comes from `findAllByOrderByPositionAscSymbolAsc` — **disabled pairs included**, so a
pair switched off at lunchtime cannot change the chart out from under someone mid-day. Adding a
pair does move which asset future days land on; the list is an input, and a longer list is a
different input.

There is deliberately no endpoint yet. Selection is the half that fails silently, so it lands
and gets reviewed on its own; the daily *mode* — one attempt a day, its own streak, the share
card, archive — is separate work on top of this.

### Two streaks, and they are not the same number

`PlayerScore.currentStreak` is **consecutive correct calls** — it resets on a miss and it is
what `score` pays a bonus on. `PlayStreak` is **consecutive UTC days with at least one call on
them** — a miss does not touch it, because playing badly is still showing up. `StatsResponse`
carries the second as `dayStreak` rather than a shorter name for exactly this reason: the
profile draws both, side by side, and a label saying only "streak" would be wrong on one of
them.

Neither is stored. `PlayStreak` folds the distinct days out of `guess_results` and
`live_predictions` timestamps (`distinctPlayDaysDesc`, a union across both — a live call counts
the day it was placed, settled or not), so it cannot drift out of step with the history it
describes and deleting a player takes it with them. A day boundary is UTC, like every other one
here, and `StatsService` reads today through the injected `Clock` so a test can pin it.

The current run counts back from **today or yesterday**. Without that grace every streak on the
site would read zero from midnight UTC until its owner next opened the game; it breaks only
once a whole day has gone by unplayed. Imported `legacy_*` figures never feed it — four totals
with no dates on them cannot say which days were played.

**Three surfaces draw a days-in-a-row number, and two of them are different numbers.** The
game tab's chip and the profile tile both show `PlayStreak` over *any* play; the daily tab
shows it over DAILY days only, so it can break while the other holds. They are labelled apart
for that reason — "ngày liên tiếp" for the general one, "ngày thử thách liên tiếp" on the daily
tab. Reusing one label for both is the mistake to avoid: adjacent tabs disagreeing about a
number under identical wording reads as a bug, not as two facts.

The game-tab chip is deliberately **not** a fifth scoreboard tile. That grid already has a
"Streak" — correct calls in a row — and two different numbers under the same word in one grid
is how a scoreboard stops being read. It is hidden when signed out and at zero alike: nothing
is recorded for anonymous play, so a streak there would be a promise that vanishes on sign-in,
and a player with no run going has nothing to protect. `refreshAccountStats` runs after every
recorded guess, so the day's first round ticks it up in front of the player — which is the
reason it is on that tab and not only the profile.

### Leaderboard

`GET /api/leaderboard` is public — anonymous callers get the board without the `me` row, and
signing in adds it. Ranked on `score` from `PlayerScore`, the same function the profile and the
game tab use, so a rank is computed from the number the player already sees.

**Admin accounts never appear.** The seeded/dev admin wallet plays far more rounds than any
real player while the app is being tested, and a public board showing staff in first place reads
as gaming their own leaderboard — worse than an empty board. Excluded once, in the same branch
that already treats "no name to rank" as meaning nobody to rank (a deleted user shares that path).

**It ranks on server-recorded results only.** The `legacy_*` columns are a browser tally folded
in at first sign-in; every figure in them is client-supplied and `isCoherent()` only rejects the
absurd. Counting them would make posting a large believable number the fastest way up the
board, so `LeaderboardService` never reads them — the visible gap is real, and deliberate: on
the seeded admin account the profile shows 642 and the board shows 140.

Score depends on the order guesses were made, so it cannot be a `SUM`. One query
(`resultFlagsByUserInPlayOrder`) walks `idx_guess_results_user_time` and the fold happens once
in Java, cached 60s — this is the only open endpoint whose cache miss scans the guess table,
which is also why it is the only read endpoint in `RateLimiter`. Denormalising onto `users` is
the next step if it ever gets slow; `docs/LEADERBOARD_PLAN.md` records the trigger.

Adding a tab means **three** edits, not two: the button, the `<main>` panel, and the `views` map
in `nav.js`. Miss the map and `activate()` hides every panel and unhides none — the tab's init
still runs, so the data is correct and the screen is blank.

### CSS conventions

Everything reads tokens from `:root` in `style.css`; both themes swap only token values, and
no drawing code knows which theme is active (SVG presentation attributes take `var()` too).

- Motion: one easing `--ease-out`, four role-named durations (`--duration-fast/normal/enter/roll`).
  Never hard-code a duration — the `prefers-reduced-motion` block collapses the tokens, which is
  the only way it reaches animations that JS writes as inline styles.
- An `animation: infinite` cannot be handled by shortening its token (that just spins it
  faster); switch it off explicitly in that block, as `.ticker-track` and `.skeleton::after` do.
- `.rolling` (odometer digits), `.skeleton`, `.pill` are the shared primitives.
- Numbers get `font-variant-numeric: tabular-nums`.

### Patterns and heatmap

`pattern/` holds two libraries — candlestick (`PatternLibrary`) and chart-shape
(`TechnicalPatternLibrary`, which uses `SwingPivotDetector`). Cards render hand-drawn SVG
illustrations; "Tìm ví dụ thật" calls `/api/{patterns,technical-patterns}/{id}/example` to
scan real stored history for a genuine occurrence.

Heatmap has two sources behind one view: crypto (CoinGecko, called straight from the browser)
and S&P 500 (`/api/market/sp500` → `YahooFinanceClient`). `treemap.js` does the layout for both.

## Notes

- **Commits carry no `Co-Authored-By` trailer.** GitHub renders that trailer as a second author
  ("dangkhoi88x and claude committed") and counts it in the repo's contributor list, which
  misrepresents who owns this work. Author and committer have always been the repo owner alone;
  the trailer was only ever text in the message body. Leave it off new commits — the 35 that
  already carry it are staying as they are rather than force-pushing a rewrite over an open PR.

- **Schema is Flyway's, not Hibernate's.** `ddl-auto` is `validate`: adding a field to an
  entity without a matching migration in `src/main/resources/db/migration` fails startup
  rather than silently altering the table. Existing databases predating Flyway are stamped
  at V1 by `baseline-on-migrate` and pick up V2 onwards.
- **Spring Boot 4.1.1 / Java 25**, and Jackson **3** (`tools.jackson.*`, not
  `com.fasterxml.jackson.*`) — this bites when hand-writing JSON handling.
- JWT uses `jjwt` with the **Gson** serializer to stay clear of Jackson 3.
- Static assets are served `Cache-Control: no-cache` (revalidate, not "don't store") and fonts
  get a year via `WebConfig`. Compression is on. Fonts are self-hosted with Latin + Vietnamese
  `unicode-range` subsets; there is no external font request.
- Blog images live in this project's Cloudinary account behind an `f_auto,q_auto` transform.
  Dropping the transform segment from the URL returns the untouched original.
- `ROUND_TOKEN_SECRET` and `AUTH_JWT_SECRET` default to dev values, and **the app now refuses
  to start on those defaults outside the `dev` profile** (`StartupSecretsCheck`). A checkout
  runs as `dev` via `spring.profiles.default`; the Dockerfile sets `prod`, and the container is
  the only way this is deployed, so a deployment that forgets the variables fails at boot
  instead of signing sessions and round answers with a key published in this repository. The
  check is `@PostConstruct`, not `ApplicationReadyEvent`, so the port is never bound — the
  first version fired after Tomcat was already accepting connections. `ADMIN_WALLETS` is empty by default, which closes `/api/admin/**` and
  `/api/media/**` entirely rather than leaving them open. `MEDIA_ADMIN_WALLETS` is still read
  as a fallback for deployments that predate roles (`AdminWallets` logs a warning); move those
  addresses over.
- Config knobs (assets, backfill start, dead-round threshold, repeat cache TTL, visible candle
  count) live under `candles.*` in `application.yaml`.
