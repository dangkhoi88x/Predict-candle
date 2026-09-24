# Player stats

Everything derived from a player's history: badges, habits, the two streaks, the leaderboard and seasons, and the retention baseline.

## Achievements

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

## Habits (player insights)

The profile's "Thói quen khi đoán" section answers where a player's calls go wrong rather than how
often they are right: `GET /api/stats/me/insights` (`InsightsService` → `PlayerInsights`, a pure
fold like `PlayStreak`). Nothing is stored.

It reads the **last 500** recorded calls, not the whole history — a habit is how somebody plays
now, and the cost stays bounded at one query for the calls and one per pair for the candles. Each
call is joined back to the candles it was made on: the last visible candle is
`start_index + visible + guess_number - 2`, and `CandleRepository.candlesAtIndexes` numbers
candles with `row_number() over (order by open_time) - 1`, the same position `findWindow`'s
OFFSET means. `InsightsFlowTest.candlesAreAddressedByTheSameIndexThatDealtTheChart` pins that
equivalence; if the two ever numbered differently every trend would be read off the wrong candles
and nothing would look broken.

What the chart had just done (`trendOf`) is the net move over the last 5 visible candles against
their **average range**, not a percentage — a 1% hour is quiet for SOL and violent for BTC.

Three thresholds keep it honest, all in `PlayerInsights`: no finding under 30 answered calls, no
bucket compared under 10, no gap under 10 points reported. **The timeout finding is the one
exception and counts every call** — found on a real account with 71 timeouts in 89 calls, whose 18
answered calls were too few for anything else, so gating it on answered calls hid the one habit
that was plainly true. Sessions are Vietnam hours, deliberately not UTC like every day boundary
elsewhere: a habit belongs to somebody's evening.

**Patterns are counted per call, on the last candle the player could see** — `RoundPatternScanner`
pointed at that one candle, the same scan that labels a finished round. Overlapping patterns (a
hammer that is also a doji) each get the call; that double counts on purpose, because the question
per row is "how do you do when this is on the chart", and both were. Rows under the bucket floor
are still listed, muted, since a player wants to see the pattern was there; only `WEAK_PATTERN`
respects the floor. Each row's name opens its library card through `CandlePatterns.reveal`, and
`profile.js` waits on `CandlePatterns.whenLoaded()` before drawing so names are never raw ids.

**The lesson card after the daily picks one thing, in a fixed order** (`daily.js`): a pattern that
completed on the last candle before a guess the player *missed*; else the top finding from the
insights endpoint; else any pattern the chart held; else why signing in and playing on will make
the card say something. The pattern part comes from the finishing guess's `context` — the only
response that carries a round's pattern marks — so it is taken at that moment and held in
`chartLesson`; a finished day read back later has no marks and falls to the finding. Guess *k*'s
last visible candle in context coordinates is `guessFrom + k − 2`. Sentences for findings live in
`insights.js` (`CandleInsights`), shared with the profile, so a habit is phrased the same wherever
it is named; it loads before `daily.js` and `profile.js`.

A finding carries only its kind, which bucket, and the gap; `profile.js` reads the figures out of
the bucket and writes the sentence. So a finding and the table under it cannot disagree, and the
response stays counts, never rates, like the retention pane.

## Two streaks, and they are not the same number

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

## Leaderboard

**A board is a UTC month** (`Season`), with all-time as a second window (`?season=2026-09` /
`?season=all`; no parameter means the month running now, so an old link opens on the current one).
Nothing resets and nothing is archived — a season is a filter over the same rows, so a finished
month reads back exactly as it stood and a player who starts today is not ranked against a year of
someone else's play. A month outside the site's life is refused rather than answered with an empty
board, because an empty board is a real answer for a quiet month. Each window caches under its own
key. The play tab's card, the rail's tag and the profile's medallion all follow the current season,
which is why they say *tháng này* rather than only "rank".

**A season medal is that month's rank asked again** — `GET /api/leaderboard/seasons?months=` returns
each finished month's podium and, for a signed-in caller, the ones they stand on. Nothing is written
when a month ends, the same bargain `Achievement` makes: medals cannot drift from the board, a month
cannot be awarded twice, and deleting an account takes them with it. They are read off the per-season
rankings the board already caches, so a medal and the board it came from cannot disagree; months
nobody qualified in drop out rather than list an empty podium, and the window is capped at
`MAX_HISTORY_MONTHS` because a cold read walks one ranking per month asked for (its own lower rate
limit, for the same reason). The profile draws the caller's medals; the board draws last month's
podium under the picker while the running season is on screen. Gold/silver/bronze come from the
medallion's own three, as a tint behind the rank rather than as its colour — gold text at 12px is
about 2:1 on a light panel.

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

The rail draws the caller's rank beside "Bảng Xếp Hạng" from the `candles:rank` event, not from
a fetch of its own. The "MỚI" tag beside Thử Thách hides for good once that tab has been opened
(`candles-seen-daily`) — a badge that never changes stops being read.

## Retention baseline

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
