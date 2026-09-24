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
| `controller/` | the 30 `@RestController`s |
| `service/` | the `@Service`s, plus `RateLimiter` and the schedulers |
| `repository/` | the 14 Spring Data interfaces |
| `entity/` | the 13 `@Entity` classes and the 7 persisted enums (`GuessMode` is PRACTICE / DAILY / ARCHIVE / CHALLENGE) |
| `dto/request/` | the records a client sends in: `GuessRequest`, `WalletVerifyRequest`, `TelegramLoginRequest`, `BlogPostRequest`, `ContentItemRequest`, `LegacyStatsRequest`, `ChallengeCreateRequest` |
| `dto/response/` | the records the server sends out, including the pieces nested inside them (`CandleDto`, `BlogPostDto`, `PlayerSummary`) |
| `domain/` | internal value records that never leave the server: `RoundToken`, `RoundSelection`, `AuthSession`, `PlayerScore`, `PlayStreak`, `DailySeed`, `DailyRound`, `HintLevel`, `Achievement`, `PatternQuizPick`, `DemoPortfolio`, `StoredMedia` |
| `security/` | `JwtService`, the filter, `WalletSignatureVerifier`, `TelegramInitDataVerifier`, `AdminAccess`, `AdminWallets`, `AdminRoleReconciler` |
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


### Rules that hold everywhere

Each of these is argued in full in the file named beside it. They are here because breaking one
fails silently — nothing crashes, a number is just wrong.

- **Derive, don't store.** Streaks, badges, seasons and season medals, retention, community
  difficulty and the demo balance are all folded out of `guess_results`, `live_predictions` or the
  trade log on read. A stored copy is a second source of truth free to drift. Before adding a
  column or a table for a number, ask whether it can be computed from rows that already exist.
  ([player-stats](docs/architecture/player-stats.md), [demo-trading](docs/architecture/demo-trading.md))
- **The server keeps no round state and trusts no client figure.** The round token is a signed JWT
  carrying the chart, mode, timeframe, misses and `iatMs` (never `iat`). Fill prices, challenge
  scores and think time are the server's; `legacy_*` columns never reach a ranking or a badge.
  ([game-rounds](docs/architecture/game-rounds.md), [daily](docs/architecture/daily.md))
- **Modes are a security boundary.** PRACTICE / DAILY / ARCHIVE / CHALLENGE are checked against the
  endpoint; challenge guesses live in their own table; an archive replay never feeds the daily
  streak. ([daily](docs/architecture/daily.md))
- **One index space.** A candle index is a position among the stored hourly rows at every
  timeframe, and `findWindow`'s OFFSET and `candlesAtIndexes`' `row_number()` must agree.
  ([game-rounds](docs/architecture/game-rounds.md), [player-stats](docs/architecture/player-stats.md))
- **No answer leaks early.** `CandleDto` never gains volume; the context chart arrives only when
  the round is over; a hint never reads past the last revealed candle.
  ([game-rounds](docs/architecture/game-rounds.md), [demo-trading](docs/architecture/demo-trading.md))
- **Nothing deals a chart the player did not ask for**, because a round's clock starts when the
  token is minted. ([frontend](docs/architecture/frontend.md))
- **Frontend files are IIFEs with no top-level `let`/`const`/`class`**, since they are joined into
  one bundle; a script in a view bundle waits on `CandleNav.ready`, not `DOMContentLoaded`.
  ([frontend](docs/architecture/frontend.md))
- **Checklists that fail with no error:** a new tab is three edits, a new admin pane five, a new
  Tiptap node three renderers. ([frontend](docs/architecture/frontend.md),
  [admin](docs/architecture/admin.md), [content](docs/architecture/content.md))

### Where the rest is written

These files are not loaded automatically. **Read the one covering an area before changing it** —
most of what they record is a bug that already shipped once.

| file | read before touching |
|---|---|
| [auth.md](docs/architecture/auth.md) | sign-in, sessions, JWTs, `SecurityConfig`, roles, `AdminAccess` |
| [game-rounds.md](docs/architecture/game-rounds.md) | practice rounds, `RoundTokenService`, `RoundCandleService`, timeframes, `HintLevel`, community difficulty |
| [daily.md](docs/architecture/daily.md) | the daily and its selection, the archive, challenge links, the pattern-of-the-day quiz |
| [live-round.md](docs/architecture/live-round.md) | `LiveRoundService`, `live_predictions`, `live.js`, the admin live pane, `CandleChart.fitLabels` |
| [demo-trading.md](docs/architecture/demo-trading.md) | `/api/demo/**`, `DemoPortfolio`, `demo-trade.js`, the terminal chart, crosshair and indicators, `CandleAggregator` |
| [player-stats.md](docs/architecture/player-stats.md) | badges, insights, either streak, the leaderboard and seasons, `/api/admin/retention` |
| [telegram.md](docs/architecture/telegram.md) | `telegram.js`, `TelegramInitDataVerifier`, the bot, group reminders, framing headers |
| [content.md](docs/architecture/content.md) | pattern libraries, heatmap and ticker, `/blog`, `/mau-nen`, `/mau-hinh`, `/tam-ly`, the sitemap, `BlogDocumentHtml` |
| [frontend.md](docs/architecture/frontend.md) | `index.html`, `nav.js`, `AppShellService`, bundling and minifying, onboarding, shortcuts, shared modules, any stylesheet and the order they load in |
| [admin.md](docs/architecture/admin.md) | `admin.html` and every `admin-*.js`, `RecentErrors` and alerts, admin stats, the blog editor, players, challenge preview |
| [operations.md](docs/architecture/operations.md) | `render.yaml`, the `Dockerfile` and AOT cache, `/healthz`, the price source, backups, a page load that looks slow |

A new architectural note goes into the file for its area, not back into this one. A new area gets
its own file and a row in this table.

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
- Static assets are served `Cache-Control: no-cache` (revalidate, not "don't store"), except
  the game page's bundle (`AppShellService`, immutable) and fonts, which get a year via `WebConfig`. Compression is on. Fonts are self-hosted with Latin + Vietnamese
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
