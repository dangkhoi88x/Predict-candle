# Game rounds

How a practice chart is dealt, played and scored: the stateless round token, timeframes, the hint ladder and community difficulty.

## Round flow (the game)

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

## Round timeframes

Practice can be played at `1h`, `4h` or `1d` (`candles.round.timeframes`,
`GET /api/practice/round?asset=…&tf=4h`; no `tf` means the stored timeframe, so a client that
predates this is unchanged). Only the hourly candles are stored — a longer bar is folded by
`CandleAggregator` through `RoundCandleService`, the same bargain the trade terminal's chart makes,
so nothing new is synced and nothing shorter than an hour can ever be offered.

**An index is still a position among the stored rows**, at every timeframe. That is what keeps one
coordinate space: `guess_results`' unique constraint still names one attempt, a 4h round and an
hourly one over the same stretch are different rounds because the *timeframe* differs, and a round
token needs no new field — the timeframe it already carries says how to read its index. Every read
a round makes goes through `RoundCandleService`: selection, hints, answers, the reveal, the context
chart and a challenge's window. Moving by a bar means moving `storedPerBar` rows, which is the one
arithmetic slip that would silently ask about a candle hours away from the one on screen.

**A bar is the exchange's clock period, never four hours from wherever the draw landed.**
`alignToBucket` moves the drawn index back to the first stored candle of its bucket — the bucket's
start is a time, and its index is how many candles closed before it, which is exactly what
`findWindow`'s offset counts, so the two agree even across a gap in history. A window that runs out
mid-bucket drops that last bar rather than asking a player to call a candle built from three of its
four hours.

**The picker is a second pill in the game toolbar** (`#tf-pill`), and choosing a timeframe deals a
chart the way choosing a pair does — a button that did nothing until the current chart ran out
would be worse than no button. The choice is remembered in `candles-timeframe`, guarded like every
other storage read, since being put back on hourly every visit is the kind of friction that stops a
feature being used. The practice axis counts back in the round's own step — "−76h" on a 4h chart,
"−19d" on a daily one, because "−456h" is a number nobody converts.

**The daily stays hourly** — it is one chart for everybody, and a score shared from it has to mean
the same thing for everyone who plays it. Challenge links carry their chart's timeframe, so a 4h
practice chart can still be sent to a friend. `InsightsService` reads back only calls on the stored
timeframe: a folded bar has no row to address by index, so those calls keep their long/short,
session and timeout figures and simply have no trend or pattern, like a call with a gap behind it.

## Progressive hints

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

## Community difficulty

How hard a chart turned out to be, from the calls already recorded on it:
`CommunityDifficultyService` folds `guess_results` for one chart's coordinates into a rate per
candle asked, and it travels on the finishing `GuessResponse` and on a finished `DailyRoundResponse`.
Derived, never stored, like the streaks and the badges.

- **Only once the caller has finished.** "62% called this one LONG" is most of an answer, so it
  passes the same gate the context chart does.
- **Only candles at least `MIN_PLAYERS` (10) people answered**, and a chart where no candle clears
  that floor sends nothing rather than an empty block, which reads as a crowd getting everything
  wrong. Ten is deliberately below the thirty-per-chart the plan wants before difficulty could
  *pay* anything: showing a rate and ranking people by one are different promises.
- **Per candle, not per chart**, because each candle is its own question and "the third one caught
  almost everybody" is the part worth reading. The daily board tints each chip with the player's own
  result, so the comparison is on the chip rather than two lines away.
- **It does not touch the score.** A chart's difficulty moves as more people play it, so paying a
  bonus for it would rewrite yesterday's score — and the rank the player already saw — every time
  somebody new turns up. Paying for it honestly would mean freezing each round's difficulty at the
  moment it was played, which is a stored per-guess number: the second source of truth this codebase
  keeps refusing. Difficulty is shown beside the score, not folded into it.
- Each mode has its own crowd: a daily and its archive replay are different populations on the same
  coordinates, and the query is filtered by mode for that reason.
