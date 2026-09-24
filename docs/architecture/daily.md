# Daily, archive, challenges and quiz

The date-seeded games and the ones built on them: the daily, its selection, the archive, challenge links and the pattern-of-the-day quiz.

## Daily challenge

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

## Daily round selection

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

## Daily archive

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

## Challenge links

A finished practice chart can be sent to a friend: `POST /api/challenges` → `/?thach=<id>`, played
through `GET /api/challenges/{id}` and `POST /api/challenges/{id}/guess`, all public.

**The advertised score is one the server signed.** A practice chart's last `GuessResponse` carries
`challengeToken` — a JWT with `type: challenge-result`, the chart, and `correct = guesses − misses`,
where `misses` rode the signed round token the whole way. Creating a challenge requires that token,
so a link cannot claim a result nobody made. `RoundTokenService.verify` refuses anything carrying a
`type` claim, so a result token cannot be spent as a round, and the reverse is checked by type.
Only practice mints one: a daily or archive chart is already everyone's, and challenging a
challenge would let a player launder a link they were sent into one saying they set it.

**Challenge guesses live in `challenge_guesses`, never `guess_results`, and that is the design.**
The creator has seen the chart's answers, so a challenge is a chart whose answers somebody already
knows; counted towards score, badges, retention or the leaderboard, a second account could farm
points off links the first made. `GuessMode.CHALLENGE` exists for the token only — the CHECK on
`guess_results.mode` would refuse a row. `RoundPlayService.play` therefore takes a `GuessRecorder`;
practice, daily and archive pass `GuessResultService::record`, challenges their own. For the same
reason the creator cannot play their own challenge (`checkToken` refuses, and `mine` returns the
chart finished with no token), and a signed-in creator asking twice for one chart gets the same id.

Everything else is the daily's shape: one attempt per signed-in player from a unique constraint,
resume from recorded rows, `checkToken` making the path's challenge and the token's chart agree.
Anonymous play is never recorded, so it never appears among `finishers`.

**The creator's name on a link is read from the account, not from `creator_name`.** That column is
a snapshot taken when the link was made; reading it meant an admin renaming an offensive name left
the old one on every link the account had sent. It is now only the name for an anonymous creator,
or a deleted one — and `AdminPlayerService.delete` overwrites it (`ChallengeRepository.forgetCreator`)
before removing the account, since `ON DELETE SET NULL` alone kept a deleted player's name on their
links. V20 clears names left behind by deletions made before that.

**The daily board plays challenges**, the way it plays archive days: `daily.js`'s `challengeId`
switches the URLs, heading, gate text, finish line (you vs the creator) and adds the finishers list;
`isToday()` keeps challenge plays out of the daily funnel events. `loadSeq` drops a stale response —
opening a challenge reveals the tab (which loads today) and then loads the challenge, and whichever
answered last used to win. `?thach=` is read on `DOMContentLoaded` and stripped from the address bar.
`AssetSeedOrderTest` deletes every table pointing at `assets`; `challenges` is on that list.

**The ops pane's "Thách đấu" card is the only place links are counted** — links made (all time and
7 days), signed-in players who played one, plays finished — precisely because their guesses are
kept out of every other total. Anonymous plays are recorded nowhere, so they are in none of them.

## Pattern-of-the-day quiz

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
