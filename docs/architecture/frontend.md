# Game frontend

The player-facing page: the shell and rail, the bundling `AppShellService` does, shared modules, deferred tabs, and the CSS rules both themes depend on.

## Frontend

Plain static files under `src/main/resources/static/` — no build step, no framework, ES5-style
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

**`?view=<name>` opens the page on that view** — the installed app's shortcuts use it, as can any
link. `nav.js` handles it on `DOMContentLoaded`, not when it loads: most views' builders are
defined by scripts after it, and activating earlier opens a tab that never builds. The parameter
(and `source`) is then stripped from the address bar; `profile` is refused, since a link cannot
know the visitor is signed in.

**The site is installable (`manifest.json`, icons rendered from `web/og/app-icon.html`) and has no
service worker, on purpose.** Content comes from the server or not at all; an offline cache is
precisely a way to show a stale chart as current.

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

Adding a tab means **three** edits, not two: the rail item (inside one group's
`.rail-group-items`, not loose in `.rail-nav`), the `<main>` panel, and the `views` map in
`nav.js`. Miss the map and `activate()` returns before it touches anything — the rail stops
responding to that item entirely, with no error to say why.

Script order in `index.html` matters: `pill.js`, `rolling.js` and `avatar.js` define shared
globals that later files call at load time.

**The browser never sees those `<script>` tags, and the page declares its files in the head.** `AppShellService` serves `/` with every local
`<script src>` joined, in the page's order, into one `/app.<hash>.js`, and every stylesheet into
one `/app.<hash>.css`; the name is a hash of the content, so both are `immutable` for a year and
the page itself is `no-cache` with an ETag. The page used to ask for 36 files, each `no-cache`,
and on the demo's 0.1 CPU they queued — 0.4-1.6s each on a first visit, and 36 revalidations on
every visit after. Nothing is generated or committed: the bundle is built from the same files at
runtime (re-read when one changes while running from exploded classes, so `process-resources`
still works), and a new script is still just a tag in `index.html`. Three consequences:

- **A tag carrying `data-view="x"` goes into that view's own bundle**, which `nav.js` fetches the
  first time view *x* opens; everything unlabelled goes into the bundle the page loads up front.
  The names reach the client as `window.CandleChunks`, written into the head beside the bundle —
  a manifest that arrived over the network would be a tab that opens empty and then fills. The
  up-front bundle went 52.9 KB → 24.4 KB gzipped, and the mobile score 95 → 99, FCP 1.5s → 1.1s.
  Three things follow, and the second one shipped as a bug before it was understood:
  - **A module in a view bundle must not wait for `DOMContentLoaded`** — that fired long before
    the bundle arrived. `CandleNav.ready(fn)` is the replacement, and called from inside a bundle
    it runs `fn` **once that whole bundle has run**, not on the spot. `daily.js` (`?thach=`) and
    `technical-patterns.js` (`?card=`) both read a deep link this way; without it every challenge
    link opened the plain game and said nothing.
    Running `fn` on the spot was the second bug, and it shipped. The bundle is still executing
    at that moment and `nav.js` has not marked it loaded. So a `CandleNav.go()` made from `fn`
    put off the view's first build until the bundle's `onload`, and that build ran *after* `fn`.
    `__initDailyView` then reset the board to today and loaded it over the challenge
    `openChallenge()` had just put there. Every `?thach=` link on a first visit opened today's
    daily. `nav.js` tags each bundle's `<script>` with `data-chunk` so `ready()` can recognise
    `document.currentScript`. `e2e/tests/challenge.spec.js` fails without this.
  - **With the bundle already in hand the builders run synchronously**, inside `activate()`,
    which is where they ran before any of this existed. `technical-patterns.js` opens a card by
    clicking its tab and then reaching for the card; deferring the builder to a microtask left it
    reaching into a view that had not been built, so `?card=` opened the right tab and no card.
    Its build is now idempotent (`initOnce`) and the link waits on it rather than on ordering.
  - **A global from a view bundle is absent until that view has been opened.** `patterns.js` is
    deliberately unlabelled for exactly this reason: the game names a pattern mid-round from it.
  A parameter that addresses a view's content needs that view's bundle whether or not the tab is
  opened, which is what `PARAM_CHUNKS` in `nav.js` is for. Once the page is quiet the rest are
  `rel="prefetch"`ed, so a first tab switch costs nothing and a bundle nobody opens is still
  never parsed or run.
- The script is `defer` and sits in the head. A plain tag stopped the parser until 119 KB had
  downloaded and run — Lighthouse put it at ~2.1s of the mobile first paint. Deferred it still
  runs after parsing, which is where it ran at the body's end anyway. The two faces the first
  paint uses are preloaded, since the stylesheet is the only thing naming them and it is 43 KB in.
  Measured on the mobile preset: score 81 → 91, LCP 4.2s → 3.1s.
  **And `fetchpriority="high"`, because `defer` alone makes it wait.** A deferred script is
  fetched at Low priority, and Chrome holds low-priority requests back until the render-blocking
  stylesheet is in — the bundle was not even requested until the CSS had finished, and on a first
  visit it is what draws the LCP (the tour). The attribute changes when it is fetched, never when
  it runs. Found by reading a live trace rather than guessed, then A/B'd on devtools throttling
  (five interleaved runs each): LCP 2536 → 2241 ms with ranges that did not overlap, for FCP
  1665 → 1731 ms, the bundle now sharing the pipe with the stylesheet.
- **The scripts are minified by the build, the stylesheet by the server**, and the split is not
  an inconsistency. Whitespace is all a stylesheet has left once gzip has run — `CssMinifier` is
  60 lines and lands within 1% of esbuild on this input — while scripts only give anything up by
  renaming locals, which needs a compiler that has no business inside a process with a tenth of a
  CPU to start with. The Closure plugin (`pom.xml`, `prepare-package`) rewrites `target/classes`
  in place, so the jar ships short files and a checkout still serves the ones it can read;
  `spring-boot:run` re-copies the sources over them, which is why dev always sees the originals.
  SIMPLE, never ADVANCED: ADVANCED rewrites property names and would break every `window.CandleX`.
  The two committed bundles are excluded — vite minified them already. Measured: the joined script
  went 120 KB → 52.9 KB gzipped, the stylesheet 39.2 KB → 20.0 KB, mobile score 91 → 95, FCP
  2.5s → 1.5s.
- **`CssMinifier` stops where meaning starts.** It drops comments and layout, the space after a
  declaration's colon and the last `;` of a block. It will not touch the space *before* a colon
  (`.card :hover` is a descendant, `.card:hover` is the card), anything inside quotes (a `content`
  string can hold `/*`), or the spaces around a combinator — `>` alone would be safe, but `+` and
  `~` also appear inside values, and one rule for all three is one rule to get wrong.
- **HTML comments are stripped on the way out** — 14.6 KB of 76.6 KB, on the one file that cannot
  be cached (18.3 KB → 11.9 KB gzipped). The source keeps every word. A plain regex is only safe
  because no comment here sits inside a script or a style.
- Each file is wrapped in its own `try`, so one that throws at load still stops only itself.
  That is safe only because every file is an IIFE — **a top-level `let`/`const`/`class` would
  become block-scoped** and invisible to the files after it.
- The stylesheet is served from the root because `style.css` names its fonts relatively.
- Any other hash gets the current file marked `no-cache`, never a 404: Render runs the old
  instance beside the new one through a deploy, and a 404 there is a page with no scripts.
- Stack traces point into the bundle; the `console.error` wrapper names the file.

**The funnel before the first recorded guess is measured by GoatCounter, not by the database.**
Retention starts at a player's first *recorded* call, and an anonymous guess is never recorded,
so nobody who opened the page and left — or played signed out — was visible anywhere. An events
table was the alternative and is the wrong trade here: it would duplicate what `guess_results`
already says for signed-in play, and page views are exactly the thing this app has no business
storing. `analytics.js` names the steps (`onboarding-*`, `first-guess`, `chart-complete`,
`daily-first-guess`, `daily-complete`, `daily-share`, `sign-in`) and GoatCounter counts them with
no cookies.

**The admin's overview links to it rather than redrawing it.** Those steps are counted outside this
database, so a funnel card here would either be empty or a second, disagreeing copy; `admin-overview.js`
turns the configured endpoint into its dashboard URL (the same string without `/count`, checked against
the shape the server validates before it becomes an `href`) and hides the link when no site is configured.

It is off unless `ANALYTICS_GOATCOUNTER` names a site code, which `SiteConfigController` turns
into the endpoint — a code and not a URL, so a mistyped variable cannot become a script source.
`/api/site-config` is cached five minutes, which is also why switching it locally seems not to
take until the browser's copy expires. `sign-in` is counted in the *exported*
`CandleAuth.applySession`, the wallet bundle's entry point: a session restored from the cookie
goes through the inner function and is not a sign-in.

**A first visit gets a three-step tour, and the game's first chart waits for it.** Both the
game and the daily deal a round the moment they are shown, and a round's clock starts from the
server's token — so a tour laid *over* a running round spends the newcomer's first guess while
they read how to make one. `app.js` therefore waits on `CandleOnboarding.gameReady()`, which resolves once the tour is closed
**and** the game view is on screen, to whether the player just asked to play: closing the tour
towards the game deals a chart at once, anything else leaves the start button up. Ending the tour on
"Thử thách hôm nay" must not deal a practice round behind the daily tab.
`onboarding.js` loads after `nav.js` (it moves views through `CandleNav`) and before `app.js`.

"First visit" means no `candles-onboarded` flag **and** none of the keys the page already wrote
before the tour existed (the browser tally, the theme, the rail layout) — a returning player is
not greeted as a stranger. The copy quotes real configuration (20 candles, 5 guesses, 20 s,
10 points, the hint order); change `candles.round.*`, `PlayerScore` or `HintLevel` and the tour
is wrong.

| Shared module | Global | Used by |
|---|---|---|
| `pill.js` | `CandlePill.attach(track, sel)` | 6 asset/filter pickers |
| `rolling.js` | `CandleRolling.update(el, text)` | price, delta, scoreboard, ticker, heatmap |
| `avatar.js` | `CandleAvatar.node(name)` | leaderboard + the play tab's board card |
| `format.js` | `CandleFormat.price/usd/compactUsd/count/percent/signedPct/clock` | everywhere a number is drawn |
| `sound.js` | `CandleSound.unlock/correct/wrong/summary/vibrate/attachToggle` | the practice round |
| `candle-chart.js` | `CandleChart.draw(svg, candles, opts)` | **every** candlestick chart on the site |
| `keys.js` | `CandleKeys.bind(view, map)` | the practice game and the daily |

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

**A shortcut presses the button a mouse would press.** `CandleKeys.bind(view, map)` takes
elements, never functions, so nothing about a round is written twice — sound, the miss count, the
funnel event and the recording all stay behind the click handlers that own them, and a key
reaching a hidden or disabled button does nothing, exactly as a mouse would. Up and down rather
than left and right, because the call is about where the price goes. A value may be a list,
meaning whichever of them is actionable: Space on the game tab is the start gate before a round
and "Biểu đồ mới" after one. The daily binds no key that deals a chart — there is one a day, and
Space landing on a start button would begin the attempt its countdown then runs on.

Five things the handler ignores, each found by trying it: a focused button or link (those answer
Enter and Space themselves, so handling them here presses twice), typing in a field, a keystroke
inside the rail's tablist (it moves the selection with the same arrows), anything carrying
Cmd/Ctrl/Alt, and every key pressed while another view or a `role="dialog"` is on screen — the
first-visit tour is exactly that, and a round's clock runs on whatever chart was dealt. The hint
line under the buttons is `(hover: hover) and (pointer: fine)` only; its base `display: none`
comes **before** the query, or source order hides it everywhere.

**Nothing deals a chart unless the player asks for one** — the start button in the chart's place
(`showStartGate` in `app.js`, `showGate` in `daily.js`), "Biểu đồ mới", picking a pair, the tour's
"Chơi thử ngay", or auto-advance after a chart the player actually finished. A chart's clock starts
when the server mints its token and cannot be paused, so dealing on page load or on opening the
daily tab spent the player's time before they had started; the demo's first six recorded calls
were all timeouts from one player who had not begun to play. The daily reads its round on reveal
to know its state but draws nothing, and pressing start reads it again for a fresh token.

**And a chain of timeouts stops.** Each expired guess mints the next guess's token, so an
unattended chart used to run itself out to five recorded misses. A timeout while the view is not
on screen, or a second in a row (`IDLE_TIMEOUT_STREAK`), records that call and shows the button
again. The recorded timeout is what still stops a round being parked; what ends is the rest of the
chart being asked of nobody. Practice abandons the chart; the daily resumes at the next guess when
signed in (signed out nothing was recorded, so it starts the day again, as a reload always did).

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

**A failed load ends in a button, not an instruction.** `CandleContent.notice(target, text, retry)`
draws "Thử lại" when a retry is given; these tabs used to say *thử tải lại trang*, which asks
somebody to throw away the round they were playing to re-ask one request. The caller supplies the
retry, because only the caller knows what to forget first — `blog.js` clears `built`,
`patterns.js` clears `built` (its fetch memo clears itself), and `technical-patterns.js` clears
`initPromise`, which otherwise hands back the same finished failure, since its build draws the
notice rather than throwing. The button disables itself while the request is out: the failure
people actually hit is a slow connection, and a pressable button there queues identical requests
at a server already struggling.

**A build must empty its container first.** After a retry it holds the notice that offered the
button, and `patterns.js`, `psychology.js` and `technical-patterns.js` only appended — so a
successful retry drew the cards *under* "không tải được". `blog.js` and `leaderboard.js` already
cleared theirs.

`/api/content/*` and `/api/assets` are `max-age=300` plus `stale-while-revalidate` (a day for the
libraries, an hour for the pairs). Both are fetched on every page load and change a few times a
year, and on the demo each cost up to 1.5s beside the page's other requests. The price is that an
admin's edit reaches a returning visitor one load late; `PublicCacheHeadersTest` pins both.

Two consequences worth knowing. `blog.js` builds on first reveal, so its catch clears `built`
— otherwise one dropped request leaves the tab empty for the whole visit with no way to ask
again. And `CandlePatterns.nameOf`, which the game tab calls to name a pattern found mid-round,
now reads what the fetch returned instead of the deleted array; it still falls back to the raw
id, which also covers being asked before the fetch lands.

## CSS conventions

Everything reads tokens from `:root` in `style.css`; both themes swap only token values, and
no drawing code knows which theme is active (SVG presentation attributes take `var()` too).

**The stylesheet is twelve files, one per area, and the order of the `<link>` tags is part of the
code.** `style.css` holds the tokens, the fonts, the shell and the primitives every view uses;
`play.css`, `profile.css`, `leaderboard.css`, `heatmap.css`, `content.css`, `live.css`,
`onboarding.css`, `daily.css` and `trade.css` hold one area each; `candles-enhance.css` stays
last. The game page still downloads one file, because `AppShellService` joins them in tag order.
It was one file of 7,536 lines, and a third of that was the admin dashboard, sent to every player.
That third is `admin.css` now, and only `admin.html` loads it: the game's CSS bundle went from
19.8 KB to 14.6 KB gzipped.

- **A rule's file is not all that matters; its position counts too.** Two selectors of equal
  weight are decided by which comes later, so moving a rule to another file can change the
  cascade without any selector changing. The split was checked by comparing the computed style of
  every element on both pages, before and after. It was run at five widths, in both themes, with
  reduced motion forced on and off, and against every admin pane. The result: no differences.
  Two cases that check caught are the reason for the next two rules.
- **`controls.css` is loaded after each page's own rules, on both pages.** It holds the ghost
  button, the wallet and the theme switch. `admin.css` restyles those under `.admin-shell` and
  relies on these base rules for the rest. `.auth-user.hidden` weighs exactly what
  `.admin-shell .auth-user` does, so the name hides only because this file comes later.
- **The reduced-motion block is split in two.** The part that collapses the duration tokens is at
  the end of `style.css`, because the admin page needs it and loads none of the game's files. The
  line that stops the ticker is in `play.css`, after the ticker's own rule: that rule starts the
  scroll with a selector of the same weight, and the later of the two wins.
- `admin.html` links `style.css`, `admin.css` and `controls.css` directly, with no bundle.
  Anything the admin page needs from the game's side belongs in `style.css` or `controls.css`.

- Motion: one easing `--ease-out`, four role-named durations (`--duration-fast/normal/enter/roll`).
  Never hard-code a duration — the `prefers-reduced-motion` block collapses the tokens, which is
  the only way it reaches animations that JS writes as inline styles.
- An `animation: infinite` cannot be handled by shortening its token (that just spins it
  faster); switch it off explicitly in a reduced-motion block, as `.skeleton::after` does in
  `style.css` and `.ticker-track` in `play.css`.
- `.rolling` (odometer digits), `.skeleton`, `.pill` are the shared primitives.
- Numbers get `font-variant-numeric: tabular-nums`.
- **Text tokens clear WCAG AA (4.5:1) on every surface they sit on**, including `--muted-2`, which
  sets 10-11px eyebrows and table heads (dark `#838389`, light `#666d79`; the old values were
  2.6-3.2:1) and the light theme's `--warn`, which the live banner writes its countdown in. Check
  a new token value against `--bg`, `--panel` and `--panel-2` before shipping it.
- **A label on an accent-filled button is `var(--bg)`, never white** (`.side-cta`, the start and
  tour buttons): white on the dark theme's `#4f8cff` is 3.2:1, the ground colour on it is 6.2:1.
  Hover mixes the accent towards `--text`, which moves away from the label in both themes.
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
