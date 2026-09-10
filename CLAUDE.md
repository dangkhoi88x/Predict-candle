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
| `controller/` | the 22 `@RestController`s |
| `service/` | the 26 `@Service`s, plus `RateLimiter` and `CandleSyncScheduler` |
| `repository/` | the 9 Spring Data interfaces |
| `entity/` | the 9 `@Entity` classes and the 6 persisted enums (`GuessMode` is PRACTICE / DAILY / ARCHIVE) |
| `dto/request/` | the 5 records a client sends in: `GuessRequest`, `WalletVerifyRequest`, `BlogPostRequest`, `ContentItemRequest`, `LegacyStatsRequest` |
| `dto/response/` | the 17 records the server sends out, including the pieces nested inside them (`CandleDto`, `BlogPostDto`, `PlayerSummary`) |
| `domain/` | internal value records that never leave the server: `RoundToken`, `RoundSelection`, `AuthSession`, `PlayerScore`, `PlayStreak`, `DailySeed`, `DailyRound`, `HintLevel`, `Achievement`, `PatternQuizPick`, `DemoPortfolio`, `StoredMedia` |
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
IIFEs, one global per file. All eleven views live in **one `index.html`**; `nav.js` toggles
`.hidden` between them.

**The page is a shell: topbar, a 236px rail down the left, views in what is left.** The rail
replaced a single row of eleven tabs — eleven peers in one strip said nothing about which of
them is the game and which is a reference page anyone opens twice a year, and the strip wrapped
to a second line on a laptop as soon as a wallet was connected. It groups the views into Chơi /
Học / Bạn & cộng đồng and is the `role="tablist"` (roving tabindex, Up/Down, Left/Right kept
from the strip it replaced).

Below 900px the rail becomes a sheet that `.tabbar`'s "Thêm" key slides up. **The rail is the
only list of views on the page** — the bottom bar repeats four of them and defers to the rail
for the rest, so there is no second menu to keep in step.

**Each of the three groups is a disclosure, and Học ships closed.** Grouping alone did not make
the rail short — eleven rows are eleven rows, and the two pattern libraries, psychology and the
blog are reference pages sitting at the same weight as the game. Closed by default the rail
opens at eight rows, all of them things a player does. The state is remembered per group in
`candles-rail-groups`; a group missing from storage keeps whatever the markup shipped, so a new
group needs no migration.

Three consequences, and only the first is obvious:

- `activate()` opens the group holding its target **before** the selection moves. Deep links
  reach the blog and both libraries from inside the views, so a target inside a closed group is
  the ordinary path, not an edge case.
- The arrow-key ring is what is on screen, so it skips a closed group the way it already skipped
  the signed-out profile tab.
- Closing the group you are standing in is allowed, which leaves the selected tab unrendered and
  the roving tabindex with nowhere to put its `0`. `syncTabStop()` hands the stop to the first
  tab still on screen in that one case — a tablist with no way in is worse than one whose stop
  is not the selected tab.

**A closed group must not swallow a signal.** The live countdown and the caller's rank are drawn
on items inside these, so a shut group carries a dot in their place — and the view on screen
counts as a signal too, since the dot is then the only thing saying where you are. `nav.js`
watches the tags for it with a `MutationObserver`, the same idiom `CandlePill` uses, so neither
`live-banner.js` nor `play-sidebar.js` has to learn the rail exists.

The group toggles are buttons inside a `role="tablist"`, which the pattern does not describe.
The alternative was hiding a real control from the accessibility tree to keep the role tidy,
which is the worse of the two.

**Activation is keyed on the view name, never on the element pressed.** Three sets of controls
point at the same views: the rail, the bottom bar, and deep links inside the views themselves.
`window.CandleNav.go(view)` is how code moves between them, mirroring `CandleAdminNav.go`, and
anything carrying `data-nav-view` gets it through one delegated handler.

`.app` is the reading column at 760px; `.app-read` (900) is for prose and headline lists,
`.app-wide` (1180) for anything laid out in more than one track. The gutter belongs to
`.shell-main`, not to the views — otherwise every view would have to agree with the rail about
how much of it there is.

**A `minmax()` minimum inside the shell must be written `min(Npx, 100%)`.** A bare minimum is a
floor the track holds even when its container is narrower, and the shell clips (that being what
rounds its corners), so an overflowing card is cut off rather than scrolled to. Two grids
already shipped this bug.

**Every mark on the page comes from the `<symbol>` sprite at the top of `index.html`, and none
of them is an emoji.** Emoji are drawn by the operating system, so the same rail was a Noto
glyph on Android, an Apple one on a Mac and a Segoe one on Windows — and beside a candlestick
chart the column read as a toy. The convention is `admin.html`'s, which already had a monoline
set: a 24×24 box, `fill: none`, `currentColor` stroke at 1.7, round caps and joins, all of it
declared once on `.icon` and inherited through the `<use>` shadow tree. A mark therefore takes
the colour of the control it sits in and needs no rule in either theme.

A sprite rather than inlining at each site because four marks appear in both the rail and the
bottom bar. It is measured out of the layout (`position: absolute; width: 0`) rather than
`display: none`, because a `<use>` whose target sits in a hidden subtree has a history of
drawing nothing. The brand mark is the one exception — inline, and filled in `--up`/`--down`
rather than stroked, because that pair of colours is the identity.

A control that swaps marks toggles `.hidden` on two of them rather than writing `textContent`:
that is what `theme.js` already did on the admin page, and `sound.js` had to learn it, because
writing text over the button erases the markup on the first press.

**The topbar sheds identity before function.** With a wallet connected and a round running it
holds brand, live banner, day streak, wallet, two buttons and the theme switch, which do not
fit on a laptop — it used to wrap and grow 40px on every page for as long as a round ran. At
1180px the brand's wordmark goes and the mark stays; at 1040px the wallet buttons drop their
labels and keep their marks, each still carrying `title` and `aria-label`. Nothing is removed,
and a label only goes from a control whose icon is unambiguous alone.

Script order in `index.html` matters: `pill.js`, `rolling.js` and `avatar.js` define shared
globals that later files call at load time.

| Shared module | Global | Used by |
|---|---|---|
| `pill.js` | `CandlePill.attach(track, sel)` | 6 asset/filter pickers |
| `rolling.js` | `CandleRolling.update(el, text)` | price, delta, scoreboard, ticker, heatmap |
| `avatar.js` | `CandleAvatar.node(name)` | leaderboard + the play tab's board card |
| `format.js` | `CandleFormat.price/usd/compactUsd/count/percent/signedPct/clock` | everywhere a number is drawn |
| `sound.js` | `CandleSound.unlock/correct/wrong/summary/vibrate/attachToggle` | the practice round |
| `candle-chart.js` | `CandleChart.draw(svg, candles, opts)` | **every** candlestick chart on the site |

**`price()` and `usd()` are two formats on purpose.** `price()` sizes decimals to magnitude,
for a column being scanned; `usd()` always shows cents, for a single figure somebody is about
to act on. Collapsing them would either drop the cents off the live round's price or put four
decimals on a market list. Anything that renders a missing value returns an en dash — guard
null *before* `Number()`, which turns null into a perfectly finite 0 and a missing price into
`$0.00`.

**Nothing fetches what another module already asked for.** `/api/stats/me` answers several
questions at once and `app.js` is already asking it after every recorded guess, so it publishes
`candles:stats` and the play tab's column listens; `play-sidebar.js` publishes `candles:rank`
off the board it fetches and the rail draws the tag. Two callers reading one figure out of two
responses can only end up disagreeing about it.

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

**Adding an admin pane is five edits**, and the two easy ones to miss fail silently in
different ways: the sidebar item, the `<section data-pane="…">`, the `PANES` array in
`admin-nav.js` (miss it and `go()` quietly falls back to overview — the nav item does nothing,
with no error), the pane's own line in the `.admin-panes[data-pane=…]` rule in `style.css`
(that list is enumerated, not generic, so a missing line leaves the section `display: none`
however right the attribute is), and an entry in `admin-search.js`'s `SOURCES` so the topbar
search can see its rows.

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

**A third partition of the same rows: `practice` / `daily` / `archive` on every bucket, and
`AdminStats.Modes` over the twelve weeks the accuracy headline covers.** The counts are summed
out of the weekly buckets rather than asked for again, so the split reaches `totals.guesses()`
by construction instead of by two queries happening to agree about which weeks they cover. The
*player* figures cannot be: distinct players do not sum across buckets — one person who
practised on two days is one player and two bucket entries — so `modePlayersBetween` counts
them over the window, and they are the one pair on that card that does not add up, since
somebody who plays both games is one player of each.

This exists because `/api/admin/retention` is meant to be read before and after the daily
challenge shipped, and a retention number that moved says nothing about the daily unless
somebody can see how much of the play *was* the daily. It sits directly above the retention
card for that reason.

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

**The player list is a page, and the tally is joined into the query that orders it.** It used
to be `findAll()` plus a grouped tally, folded and sorted in Java: right at fifty accounts and
wrong in a way that never announces itself, because the failure is a screen that gets slower
every week. `UserRepository.playerPage` does the join, the search and the ordering in one
query; `countPlayers` gives the pager a denominator that means "matching what you typed"
rather than a headcount that would contradict the rows under it. Search is `like` over the
lowered address and display name, deliberately *not* the topbar search's diacritic folding —
that one matches rendered Vietnamese titles, this one matches a name or an address somebody is
pasting in.

`GET /api/admin/players/{id}` is the drill-down: the account's history split by game and by
pair, its live calls counted three ways (`calls` / `settled` / `correct` — accuracy on live
calls is against settled ones, since a round still running is not one the player got wrong),
the imported browser tally shown apart and never added to anything, and the last 25 guesses and
live calls. Read-only, like the list it opens from: the same bargain that keeps roles in
configuration applies to totals.

**`.asset-actions` sized its first two buttons by position, and that was a shared class.** The
30px square is for the pairs table's ↑/↓; unscoped it pinned the first two buttons of every
actions cell, which is invisible while one table uses the class and a row of overlapping labels
the moment another puts three worded buttons in one. It is `#asset-table`-scoped now — position
is not a property a shared class can style on.

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

### Demo trading

Paper trading on live prices: play money, real quotes, **no leverage and therefore no
liquidation**. `/api/demo/portfolio`, `/api/demo/trade`, `/api/demo/reset`, all behind
`.authenticated()` — a portfolio that belongs to nobody cannot be held to a balance.

**There is no balance column anywhere.** Cash, holdings, cost basis and realised P&L are folded
by `DemoPortfolio` out of an immutable trade log, the same shape as `PlayStreak` and the badges.
A stored balance is a second source of truth, and every way it drifts — a partial update, a
retried request, a crash between two writes — looks to the player like money appearing or
vanishing for no trade.

`demo_accounts` holds no money either. It exists to be **the row a trade locks**: because the
balance is derived there is no column to update atomically, so nothing would otherwise stop two
concurrent buys from both reading the same cash, both finding it enough, and both inserting.
`openedAt` is the other reason — a reset moves that mark and the fold stops reading before it,
so rewinding deletes nothing.

**The client never supplies a price.** Every fill is priced from `LivePriceService` inside the
transaction that records it. Same rule the round token enforces for guesses: any figure the
client supplies is a figure the client can choose. A buy says how much cash to spend and a sell
says how much to release, because that is how each side is actually decided.

`feeBps` is not decoration — with no cost per trade a player can round-trip as often as the
price ticks and let variance do the rest, and the portfolio ends up measuring how often somebody
traded rather than how well. `aRoundTripAtAnUnchangedPriceLosesTheFees` pins that.

Two profit numbers, and confusing them is a visible bug: `realisedPnl` is cash that has come
back from closed trades, `unrealisedPnl` is what open positions are worth on paper this second.
A position whose price the feed cannot supply reports null value rather than zero, and stays out
of equity — a number that quietly drops a holding is worse than a gap.

`LivePriceService` is separate from `LiveRoundService`'s own price read rather than extracted
from it: that one answers "the candle covering this round" and validates its cache against the
round's open time, while this answers "the price right now" with no round in the picture.

The terminal is a tab of its own (`demo-trade.js`). It computes nothing — every figure comes
from `/api/demo/portfolio`, because a client doing its own arithmetic would eventually disagree
with the server about how much money someone has. Signed out it shows a prompt rather than an
error: a 401 is the expected answer to "show me a portfolio" from someone who has none, and the
tab stays visible so the feature is discoverable.

**`DemoTrade` rounds to the column's scale in its constructor, and that is load-bearing.**
`numeric(30, 10)` truncates, so a response built in the same transaction as the insert reported
a longer number than the row would hold, while every later read returned the stored one. A
client that echoed the longer number back — which is exactly what "sell all" does — was told it
was selling more than it held. Quantity rounds DOWN specifically: rounding up would release an
amount that is not there. `theReportedQuantityIsExactlyWhatCanBeSoldBack` pins it, and it took
three attempts to write a version that actually fails without the fix — a pinned round price
divides too cleanly, and reading the quantity back through `portfolio()` after a flush reads the
database rather than the response the browser keeps.

**Demo P&L is deliberately not ranked, and that is settled rather than pending.** A resettable
balance plus a leaderboard means retrying until a lucky run, and the alternatives — seasons, or
forbidding resets — both cost more than the board is worth. `resets` is still counted on the
account in case that is ever revisited.

**The admin's copy folds through the same `DemoPortfolio`, and that is the whole design of
`AdminDemoService`.** Two ways of computing one balance is how an admin page ends up disagreeing
with a player about their money — and the player would be right, because theirs is the number
the trades produced. `fillsForAccounts` supplies a whole page of accounts in one query rather
than one fold per row; the mark a reset moved is different for every account, so it comes from
the join to `demo_accounts` instead of a parameter, which is what keeps a reset meaning the same
thing on both screens.

Three things there are worth knowing before extending it:

- **`accounts` and `tradingAccounts` are different numbers on purpose.** Opening the terminal
  creates the row, so the gap between them is how many people looked at paper trading and never
  placed a trade — the question the header exists to answer.
- **Cash and realised P&L need no prices and are always exact; holdings value, unrealised and
  equity go null together when any held position has no price.** The player's own view decides
  that per position, which is right for a list of their positions; a whole-account figure that
  quietly dropped a holding would be worse than a gap on a page somebody is comparing accounts
  across.
- **`tradesBeforeReset` is the one figure no player can see, and it exists to stop a reset
  looking like data loss.** A reset moves `opened_at` and deletes nothing, so an account can
  hold hundreds of rows while showing three trades — invisible from inside the game, and the
  first thing that reads as corruption from outside it.

The only write is the player's own `reset`, called rather than reimplemented. There is
deliberately nothing that credits an account, edits a fill or sets a balance: the balance is not
a column, and a screen that could adjust one would be inventing the second source of truth V17
was written to avoid.

The terminal is the one view that is not a reading column: `.app` caps at 760px, which is right
for a chart with two buttons under it and far too narrow for a market list, a chart and an order
ticket side by side — at 760 the middle track collapsed to about 120px. `.app-wide` gives the
trade tab the same 1180px the nav already uses. Below 1100px the chart takes a full row of its
own and the other two drop under it, rather than squeezing the one thing that needs width.

Account figures are written as plain text, **not** through `CandleRolling`. That odometer
animates a strip of digits and needs the `.rolling` class to clip it; these values carry currency
symbols and separators, and without the class every digit of the strip renders — which is
exactly what happened.

**The chart's five timeframes come from two different places, and the split is not an
optimisation.** 4h and 1d are folded out of the stored hourly candles. 1m and 15m cannot be —
those minutes were never recorded, and an hourly candle cannot be taken apart into the sixty
that made it — so `IntradayCandleService` fetches them from the exchange and caches them for
twenty seconds. Falling back to stored candles for a short timeframe would draw hourly bars
under a "1m" label, which is a chart that lies rather than one that is missing;
`timeframesShorterThanTheStoredOneComeFromTheExchangeRatherThanBeingInvented` fails with
`expected 60 but was 3600` if that guard is removed.

**Zoom is how many candles are on screen, not a scale factor** — the renderer fits whatever it
is given, so fewer bars is more detail and "zoom in" walks the count *down*. The chart fetches
the widest step once and every zoom press slices that array, so neither button costs a request.
Stepped rather than a free number because a linear step does nothing at the wide end. The
zoom level survives a timeframe switch, since it is a display preference rather than a property
of the market.

Panning moves the same slice back through the fetched array — buttons, dragging the chart, and
a horizontal wheel, none of which touch the network. The wheel only claims a gesture that is
more sideways than vertical, so scrolling the page over the chart still scrolls the page, and
`touch-action: pan-y` keeps that true on a phone.

**The live price line is drawn only while the newest candle is on screen.** `CandleChart` widens
its price scale to fit that line, so leaving it on a chart panned back three months would squash
every candle into a band at one edge to make room for a price none of them ever traded at. Zoom
survives a timeframe switch and pan does not: zoom is how much to look at, pan is where you were
looking, and reopening the tab should show now.

**MA(20/50) and RSI(14) are computed in the browser**, unlike the practice game's hint average
which the server computes. The difference is not inconsistency: there the average is *gated* — a
hint the player has not unlocked — so it cannot be sent early. Here the candles are already in
the client, there is nothing to withhold, and the readings have to be recomputed on every pan
and zoom anyway.

**Both are computed over the whole fetched series and then sliced with the candles**, never over
the visible window alone. An average is a property of a candle within its series, not of the
view: per-window would leave the left edge blank and, worse, change the value shown for the same
candle the moment anyone panned. The test for it is that the line has a point for every visible
candle and starts at the left edge rather than twenty bars in.

RSI uses Wilder's smoothing rather than a plain average of the last fourteen changes, which
drifts away from what every other terminal shows for the same candles. It gets its own pane:
an oscillator bounded 0-100 shares no units with a candle, and overlaying it either flattens the
candles or leaves a meaningless squiggle across them. `CandleChart.draw` takes `lines` — a list
of overlays — rather than the single `movingAverage` it used to; the daily tab's hint is one
entry in that list.

**A fill announces itself from the server's own row, never from what was typed.** `POST
/api/demo/trade` returns the whole account, `recent` newest first, so `recent[0]` *is* the trade
that just happened — quantity, price and fee as the server recorded them. Echoing back the form's
numbers would be wrong in three ways at once: a buy is ordered in dollars and fills in coins, a
sell is priced while the request is in flight, and `DemoTrade` rounds to the column's scale on
the way in. That last one is the sell-all bug, which is exactly what happens when the client
believes its own arithmetic about a trade.

The toast is `position: fixed` so it cannot push the order ticket around at the moment somebody
is clicking in it, and it lives inside `#view-trade`, which is what makes leaving the tab take it
with them. Its entrance is on a class the script removes and re-adds (reading `offsetWidth`
between the two), because a second fill while the first toast is still up has to replay an
animation on an element that never left. The row that landed flashes in the neutral accent
rather than the side's colour — the row's own mark already says buy or sell, and the flash is
answering "which of these is the one I just did".

**A holding is a control, not a readout.** The rows under "Đang giữ" are `<button>`s that select
that pair — the shortest route from "I own this" to its chart, and the same selection language as
the market list (row tints, only the symbol takes the accent). That list sits *below* the
terminal, so picking from it scrolls the chart into view **only when the chart is actually off
screen**: yanking the page on a desktop where it was already visible is its own kind of wrong.
Smooth scrolling is motion no duration token can reach, so `prefers-reduced-motion` is read in
the handler rather than left to CSS.

**The status line under the ticket carries two kinds of message and only one is red.** "Chưa giữ
X nào để bán" explains a disabled button; "not enough cash" is the server refusing. Both were red
until a successful sell-all started announcing itself beside a red line saying the account holds
nothing — two statements that read as one of them being a bug. `setStatus(text, isError)` is the
only way that line is written now, including when it is cleared, so a hint can never inherit the
`is-error` class from a refusal before it.

**The crosshair is a layer over a chart already drawn, never a redraw.** `CandleChart.draw`
empties its svg and rebuilds every node, so following the pointer by redrawing would put a few
hundred DOM insertions between the mouse and the picture. Instead `draw` now **returns the frame
it drew in** — the plot rectangle, the candle step, and `cx`/`py`/`indexAt`/`priceAt` — and
`crosshair(svg, frame, point)` builds its handful of nodes once and only rewrites their
attributes after that. `drawIndicator` returns the same shape, which is how the RSI pane gets the
same vertical line. Callers that ignore the return value (the daily tab, the pattern quiz, the
live popup) are unaffected; an empty chart returns null rather than undefined so "nothing drawn"
is a value rather than an absence.

Four things about it are less obvious than they look:

- **The charts are drawn with `preserveAspectRatio="none"`, so x and y stretch by different
  factors** and no single ratio converts a screen point into chart units. `getScreenCTM()`'s
  inverse is the conversion that survives a window resize; a `rect.width / viewBox.width` scale
  applied to both axes is right until someone drags the window.
- **Every redraw wipes it**, so `drawChart` puts it back from the remembered pointer position
  rather than waiting for the next mouse move — otherwise a pan, a zoom or a price poll leaves a
  chart with no crosshair under a stationary cursor.
- **The vertical line snaps to a candle, the horizontal one follows the pointer.** Between two
  candles there is no data to report; between two prices there is a perfectly real level someone
  is measuring against.
- **Nothing tracks while the chart is being dragged, and nothing tracks a finger.** During a drag
  the pointer is moving the picture rather than measuring it, and a touch has no hover state to
  report — the gesture is already spoken for by the pan.

The readout row falls back to the newest candle whenever the pointer is off the chart, rather
than blanking: a terminal that clears the price the moment you look away sends you back to the
chart to read what you just saw. Its `KL` figure is `volume × close`, in dollars, because
**Binance counts volume in the base asset** — the same conversion the 24h turnover already does,
and the same four-orders-of-magnitude error if it is skipped.

**Volume reaches the chart through `DatedCandleDto`, and the reason that is safe is a gate in a
different feature.** Volume is a *hint* in the game, released only after a miss (`HintLevel`).
The end-of-round context chart is sent as `DatedCandleDto`, so every candle in it now carries
volume — including the ones the player was asked to call. That is fine only because
`RoundPlayService` builds the context when `sessionComplete` and never before, and
`ProgressiveHintFlowTest.theContextChartArrivesOnlyOnceTheRoundIsOverBecauseItCarriesVolume` is
what holds that shut: send the context one guess early and the volume hint is free, and the whole
ladder stops meaning anything. **`CandleDto`, the record sent during play, must never gain a
volume field** — that is the hint's own door, and `RoundHintService` is the only thing allowed to
open it.

Nothing was added to the database for this: `candles.volume` is `numeric(24, 8) NOT NULL`,
`CandleAggregator` already summed it, and the exchange already reported it. The whole feature was
two `.map()` calls in `DemoTradingService.chart()` that had been dropping the field — which is
why both of its tests assert through `chart()` rather than against the aggregator. A unit test of
the fold cannot see the line that loses what the fold produced.

Intraday candles are deliberately **not stored**. A minute of history is sixty times the rows an
hour is, for a chart nobody looks at twice, and it would need its own sync, backfill and gap
handling. The client does not cache them either — keeping a minute chart across a tab switch
shows a picture that is quietly minutes old.

**4h and 1d charts are folded from the stored hourly candles**, not synced separately —
`CandleAggregator`, driven by `GET /api/demo/chart?tf=`. Only one timeframe is ever stored, so
there is nothing new to keep in step with the hourly sync.

Buckets come from `Timeframes.currentPeriodStart`, which counts periods from the epoch the way
exchanges do. **Chunking the list into groups of four instead would be simpler and wrong**: the
grouping would depend on how many candles happened to be fetched, so one 4h bar would cover
01:00–05:00 for one request and 02:00–06:00 for the next, and every bar would shift each time the
sync landed. `thesameHoursGiveTheSameBarsHoweverManyWereFetched` pins that.

The oldest bar of a window is dropped when it would be a partial period — the window started
mid-bucket, and drawing it would show a short candle that never existed. The *newest* partial bar
is kept, because that is the period still forming. The chart cache is keyed by symbol **and**
timeframe, or a 4h view gets served whatever happens to be cached for that symbol.

Asking for 120 bars returns 120 bars at any timeframe — a longer timeframe covers more time
rather than showing fewer candles, which is what the test asserts (the first version asserted
falling bar counts and failed for the right reason).

**Binance reports volume in the base asset.** 24h turnover is `Σ(volume × close)` per candle;
showing the raw figure with a `$` in front of it is off by the price of the asset, which on BTC
is four orders of magnitude ($10.2K rather than $815M).

### Pattern-of-the-day quiz

One pattern to name a day, on a real chart, from `PatternQuizService` — `GET
/api/pattern-quiz/today` and `POST /api/pattern-quiz/answer`. Reading is public; answering needs
an account, because an answer nobody owns cannot be held to one a day.

It reuses the matchers behind the "Mẫu Nến" library but **not** `PatternExampleService`: that
one picks with `ThreadLocalRandom` over a history that grows hourly, which is both mistakes
`selectDailyRound` already had to fix. Same two fixes — seed the pick, and count only candles
that closed before midnight.

**The rule the feature stands on: a window is usable only if exactly one pattern in the library
matches at its end.** Real charts overlap — a hammer with a small enough body is also a doji —
and marking a player wrong for naming a pattern that genuinely is present reads as a broken quiz
rather than a hard one, and cannot be argued with after the fact. If a pattern has no clean
occurrence anywhere, the day walks on to the next pattern in its own seeded order; the date
still decides, the data only decides how far down that order it looks.

`everyDaysQuestionMatchesExactlyOnePattern` walks **thirty** days, not one, and that matters: a
single day passes with or without the filter, because most windows are unambiguous anyway. The
first version of this test did exactly that and proved nothing. Over thirty days an unfiltered
selector reliably trips — 2026-03-13 produces a window that is both a shooting star and a
morning star.

**Storage is its own table on purpose.** `guess_results` answers are CHECK-constrained to
LONG/SHORT; a pattern answer is one of thirteen ids. The consequence is deliberate: nothing that
reads `guess_results` — score, the leaderboard, retention, badges — can see these rows. Naming a
pattern and calling a direction are different skills and one score covering both would say less
than either. **The day streak is the single exception**, unioned into `distinctPlayDaysDesc`,
because answering is turning up and that is all that streak claims to measure.

`ON DELETE CASCADE` follows `live_predictions` rather than `guess_results`: a cascade cannot be
forgotten the way another explicit delete in `AdminPlayerService` could be.

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
  *including* the candle about to appear, and the renderer ignores entries past the candles it
  has.
- **The volume strip comes out of the price plot**, in `candle-chart.js`. The viewBox is fixed
  by the caller, so candles make room rather than the chart growing; the strip is only reserved
  once volume is actually unlocked, leaving an unhinted chart exactly as it was.

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

The chip is deliberately **not** a fifth scoreboard tile. That grid already has a "Streak" —
correct calls in a row — and two different numbers under the same word in one place is how a
scoreboard stops being read. It lives in the topbar instead, so a player reading the blog or
working through the archive can still see the run they are protecting. `refreshAccountStats`
runs after every recorded guess, so the day's first round still ticks it up in front of the
player, which was the reason it was ever on the game tab rather than only the profile.

It is hidden when signed out and at zero alike: nothing is recorded for anonymous play, so a
streak there would be a promise that vanishes on sign-in, and a player with no run going has
nothing to protect.

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

Adding a tab means **three** edits, not two: the rail item (inside one group's
`.rail-group-items`, not loose in `.rail-nav`), the `<main>` panel, and the `views` map in
`nav.js`. Miss the map and `activate()` returns before it touches anything — the rail stops
responding to that item entirely, with no error to say why.

The rail draws the caller's rank beside "Bảng Xếp Hạng" from the `candles:rank` event, not from
a fetch of its own.

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
- Selection and hover are **tints, not new colours**: `--tint-accent` / `--tint-accent-strong`
  are `color-mix(in oklab, var(--accent) 12%/18%, transparent)`, so one rule works in both
  themes and over whatever ground it lands on. `--overlay-soft` is the neutral equivalent.

**The trade terminal is one frame, and its pill rule is scoped on purpose.** `.trade-terminal`
is a single bordered grid with `overflow: hidden` and no gap — the columns are separated by
`border-left`, not by cards with their own shadows, which is what keeps a three-column
terminal from reading as three unrelated panels. Radii are 4/6/8, controls are borderless
and tint on hover, and every micro-label (`.trade-account-label`, `.trade-stat-label`,
`.trade-list-head`, `.trade-rsi-label`) shares one style.

The active-timeframe rule is written as `#view-trade .pill-option.active`. `.pill` appears 47
times across the app and the nav's sliding indicator is positioned from the shared rule, so
restyling `.pill-option.active` globally moves the nav underline. Scope anything that only
means something inside the terminal.

Same reason the trade tab no longer borrows `profile-*` classes: it has its own
`.trade-positions` / `.trade-fills` / `.trade-section` / `.trade-empty`. Sharing them meant
every profile tweak had to be checked against a page it was not written for. On a selected
market row only the **name** takes the accent — the price and the day's move are data, and
tinting them both costs legibility and fights the up/down colour they are read by.

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

- **Merging a stack of PRs: never `--delete-branch` until the whole stack is in.** These land
  as chains — each PR based on the one before it — and deleting a base branch on merge makes
  GitHub **close** the PR sitting on top of it rather than retarget it to `main`. A closed PR
  cannot be retargeted, so the only way back is to push the deleted branch again from its SHA,
  reopen, retarget, merge. This has cost two recoveries; the second happened after the first
  was misdiagnosed as a scripting error, which it was not — the branch deletion is the cause.

  ```bash
  gh pr merge <n> --merge          # no --delete-branch
  gh pr edit <n+1> --base main     # retarget while it is still open
  # delete the branches once the whole stack has landed
  ```

  Retarget the next PR **before** merging the current one where you can: a PR already pointing
  at `main` cannot be orphaned by anything that happens underneath it.

- **A branch named `assets/*` is not part of any stack, and the cleanup pass must skip it.**
  These are orphan branches holding the screenshots a pull request embeds — they exist so
  `main` never carries PNGs no build reads and no reviewer diffs. `raw.githubusercontent.com`
  resolves by ref rather than by object, so deleting or renaming one turns every image in the
  pull request it illustrates into a 404, including after that PR has merged, which is exactly
  when somebody reading back through the history wants to see them. Each carries a README
  saying so. `assets/rail-groups-screenshots` is the first.

- **CI is red on `main` only when it is really broken.** It used to be red permanently because
  three test classes read whatever the first-run Binance backfill had left in the database, and
  GitHub's runners are geo-blocked by Binance (HTTP 451) — so the suite was quietly asserting
  that somebody had already run the app on this machine. `CandleFixture.seedIfEmpty` is the fix
  and the rule: **a test that needs candle history seeds its own**, and never assumes the table
  has any. Reproduce CI locally with an empty database and no exchange:

  ```bash
  ./mvnw test -Dspring.datasource.url=jdbc:postgresql://localhost:5544/candles_ci -Dcandles.binance.base-url=http://127.0.0.1:9
  ```

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
