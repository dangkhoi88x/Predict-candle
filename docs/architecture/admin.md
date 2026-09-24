# Admin frontend

`admin.html`: pane switching, the ops pane and its persisted error log, stats, the blog editor, players, and the challenge preview.

## Admin frontend

`admin.html` is a second, separate page: a dashboard shell — sidebar, sticky topbar, and ten
panes of which exactly one shows. It shares `style.css`, `controls.css`, `theme.js` and `auth.js`
with the game and nothing else; its own rules are `admin.css`, which the game page never loads.

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
with no error), the pane's own line in the `.admin-panes[data-pane=…]` rule in `admin.css`
(that list is enumerated, not generic, so a missing line leaves the section `display: none`
however right the attribute is), and an entry in `admin-search.js`'s `SOURCES` so the topbar
search can see its rows.

**The ops pane lists what has failed lately, and those rows outlive the process.** `RecentErrors`
collects from four places — `GlobalExceptionHandler` (an upstream call it turned into a 502, with
the reason), `ErrorRecordingFilter` (an exception nothing handled — outermost filter, records and
rethrows), `CandleSyncScheduler` (a pair that failed to sync) and the Telegram broadcaster — and
writes them to `app_errors` (V21). It rides on the ops snapshot as `recentErrors`, pinned by
`OpsSnapshotTest`. Consecutive repeats of one failure fold into a row with a count, because an
exchange ban raises the same error on every poll of every open tab and fifty copies would push out
the one different error worth seeing. Only the *newest* row folds, and only inside
`candles.errors.fold-window` (1h): a different failure in between starts a new row, and a ban that
ran all night is a different episode from the same ban next week.

**It used to be a deque in memory, and the restart is what changed that.** That version answered
"is something failing right now" and nothing about what failed while nobody was looking — and it
was emptied by every deploy, and by every night Render restarts a free instance. Errors are events
rather than derived state, so storing them invents no second source of truth: nothing else in the
app knows one happened.

Three things make it safe to write from inside a failure:

- **`AppErrorStore` is its own bean.** `@Transactional` is a proxy and a class calling its own
  method does not go through one, so a private method here would silently join the caller's
  transaction — the one being rolled back, which is where the row would go with it.
- **`REQUIRES_NEW`**, for that same reason: `GlobalExceptionHandler` records while the request's
  transaction is already doomed.
- **Recording never throws.** A database that is itself the problem must not turn one failure into
  a different one; a failed write goes to the log and no further, and a failed read leaves the rest
  of the ops snapshot intact.

**`ErrorAlertService` is the half that reaches somebody**, through the bot this app already runs:
a failure that starts its own row is announced to `TELEGRAM_ALERT_CHAT_ID`, with a button to the ops
pane. The cheap version of an error tracker on purpose — a third-party account for a demo whose
whole error history is a handful of exchange bans buys less than a message in the chat the owner
already reads.

- **Only a new episode.** `AppErrorStore.record` returns whether the row was new or folded, and a
  repeat of something already on the pane is not news — an exchange ban would be a message a second.
- **At most one message per `alert-cooldown` (15m)**, and the ones held back are counted in the next
  message rather than dropped: a night where every poll fails differently is exactly when a phone
  has to stay usable.
- **Its own daemon thread**, so an alert never puts an HTTP call to Telegram in front of the request
  that failed, with a bounded queue that discards rather than grows.
- **A failed send is logged and forgotten.** The row is already stored, which is the part that had
  to survive; a second error raised by reporting the first would be the worst of both.

`ErrorRetentionScheduler` trims nightly by age (`candles.errors.retention`, 14 days) **and** by row
count (`max-rows`, 2000). Both are needed: age keeps the table a picture of the last fortnight, and
the cap stops one bad night — a different error on every poll — filling it inside that window. The
card above the table still counts the last hour only, so a morning's error that has stopped does
not keep a card red.

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

**Two kinds of account share the list.** A Telegram account's key is `tg:<id>` in the column a
wallet address fills, so `PlayerSummary.login` (WALLET / TELEGRAM) is read off that prefix server-side
and the page never has to know it; the list takes `login=wallet|telegram`, the ops card counts
`telegramAccounts`, and wording that said *ví* for "account" now says *tài khoản*. A `tg:` key is
shown whole — shortening it like an address cut the id somebody was trying to read.

`GET /api/admin/players/{id}` is the drill-down: the account's history split by game and by
pair, its live calls counted three ways (`calls` / `settled` / `correct` — accuracy on live
calls is against settled ones, since a round still running is not one the player got wrong),
the imported browser tally shown apart and never added to anything, and the last 25 guesses and
live calls. It also counts the three games whose rows live in tables of their own and so reach
none of those totals — quiz answers, challenge links (sent, played, finished) and demo trades
(since and before the last reset). Demo is counted rather than valued: a balance needs live prices,
and the demo pane already folds one. Read-only, like the list it opens from: the same bargain that keeps roles in
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
