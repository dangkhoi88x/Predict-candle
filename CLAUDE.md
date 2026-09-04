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
| `controller/` | the 18 `@RestController`s |
| `service/` | the 19 `@Service`s, plus `RateLimiter` and `CandleSyncScheduler` |
| `repository/` | the 6 Spring Data interfaces |
| `entity/` | the 6 `@Entity` classes and the 4 persisted enums |
| `dto/request/` | the 5 records a client sends in: `GuessRequest`, `WalletVerifyRequest`, `BlogPostRequest`, `ContentItemRequest`, `LegacyStatsRequest` |
| `dto/response/` | the 17 records the server sends out, including the pieces nested inside them (`CandleDto`, `BlogPostDto`, `PlayerSummary`) |
| `domain/` | internal value records that never leave the server: `RoundToken`, `RoundSelection`, `AuthSession`, `PlayerScore`, `StoredMedia` |
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
next-candle mini-game does. `
`LiveRoundService` reads the wall clock through an injected `Clock` bean (`ClockConfig`), not
`Instant.now()` directly — a test can pin it to an instant known to be inside a round's open
window. This is not a style preference: `LiveRoundFlowTest` used to read `Instant.now()`
independently of the service, which agreed right up until a build happened to run in the ~8
minutes of every hour a round is locked, and a predict() call the test expected to succeed came
back 400. It reached `main` this way before anyone noticed the pattern.

GET /api/live/round` and `GET /api/live/history` are public;
`POST /api/live/predict` needs a wallet, enforced in `SecurityConfig` the same way
`/api/stats/**` is.

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

Frontend is deliberately not a live-updating candlestick chart. A big rolling price, a sparkline
of the last `candles.live.history-size` closes, a lock/close countdown reusing `.guess-timer`,
and a pool-split bar are what `live.js` renders — same visual language as practice
(`.guess-btn.long/.short`, `--up`/`--down`), built on first reveal like heatmap and blog.

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
