# Demo trading

Paper trading on live prices: the derived balance, fills priced by the server, the terminal, its chart and indicators, and the admin view.

## Demo trading

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
