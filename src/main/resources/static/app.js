(function () {
    "use strict";

    var STORAGE_KEY = "candleGuess.stats.v1";
    var MUTE_STORAGE_KEY = "candleGuess.muted.v1";
    /** Set once this browser's tally has been carried into some account. */
    var STATS_CARRIED_KEY = "candleGuess.statsCarried.v1";
    var CANDLE_STEP_SECONDS = 3600;
    var SVG_NS = "http://www.w3.org/2000/svg";

    var UP = "var(--up)";
    var DOWN = "var(--down)";
    var GRID = "var(--muted)";
    var SURFACE = "var(--panel)";
    var ACCENT = "var(--accent)";
    var HOVER_BADGE_BG = "var(--text)";
    var HOVER_BADGE_TEXT = "var(--panel)";

    var W = 1000, H = 300;
    var PAD = { top: 12, right: 54, bottom: 22, left: 6 };

    /*
     * Seeded with the pairs this file was written against and replaced at load by whatever the
     * server says is on offer, so a pair added from the admin screen appears in the picker
     * without a deploy. Keeping the literal means a failed request leaves a playable game
     * rather than an empty picker.
     *
     * `compact` is not sent by the server: it only decides whether prices render as "$78.2K"
     * or "$2,456", which follows from the price, not from the pair. Derived from the first
     * chart's own numbers below.
     */
    var ASSET_META = {
        BTCUSDT: { symbol: "BTC", name: "Bitcoin", compact: true },
        ETHUSDT: { symbol: "ETH", name: "Ethereum", compact: false },
        BNBUSDT: { symbol: "BNB", name: "BNB", compact: false },
        SOLUSDT: { symbol: "SOL", name: "Solana", compact: false },
    };

    function metaFor(symbol) {
        return ASSET_META[symbol] || { symbol: symbol, name: symbol, compact: false };
    }

    var el = {
        chart: document.getElementById("chart"),
        guessLong: document.getElementById("guess-long"),
        guessShort: document.getElementById("guess-short"),
        nextChart: document.getElementById("next-chart"),
        guessProgress: document.getElementById("guess-progress"),
        viewGame: document.getElementById("view-game"),
        guessTimer: document.getElementById("guess-timer"),
        guessTimerFill: document.getElementById("guess-timer-fill"),
        guessTimerValue: document.getElementById("guess-timer-value"),
        roundIdentity: document.getElementById("round-identity"),
        roundContext: document.getElementById("round-context"),
        rcChart: document.getElementById("rc-chart"),
        rcSub: document.getElementById("rc-sub"),
        rcPatterns: document.getElementById("rc-patterns"),
        rcPatternsList: document.getElementById("rc-patterns-list"),
        status: document.getElementById("status"),
        resultBanner: document.getElementById("result-banner"),
        score: document.getElementById("score"),
        streak: document.getElementById("streak"),
        bestStreak: document.getElementById("best-streak"),
        accuracy: document.getElementById("accuracy"),
        assetButtons: Array.prototype.slice.call(document.querySelectorAll("#asset-pill .pill-option")),
        marketSymbol: document.getElementById("market-symbol"),
        marketName: document.getElementById("market-name"),
        marketPrice: document.getElementById("market-price"),
        marketDelta: document.getElementById("market-delta"),
        marketOhlc: document.getElementById("market-ohlc"),
        marketCard: document.querySelector(".market-card"),
        soundToggle: document.getElementById("sound-toggle"),
        gameStreak: document.getElementById("game-streak"),
        gameStreakValue: document.getElementById("game-streak-value"),
    };

    // ---------- sound + haptic feedback ----------

    var muted = localStorage.getItem(MUTE_STORAGE_KEY) === "1";
    var audioCtx = null;

    function updateSoundToggleUi() {
        el.soundToggle.textContent = muted ? "🔇" : "🔊";
        el.soundToggle.setAttribute("aria-pressed", muted ? "true" : "false");
    }

    function unlockAudio() {
        if (muted) return;
        if (!audioCtx) {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (!Ctx) return;
            audioCtx = new Ctx();
        }
        if (audioCtx.state === "suspended") audioCtx.resume();
    }

    function tone(freq, startDelay, duration, type, peakGain) {
        if (muted || !audioCtx) return;
        var startAt = audioCtx.currentTime + startDelay;
        var osc = audioCtx.createOscillator();
        var gain = audioCtx.createGain();
        osc.type = type;
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(0, startAt);
        gain.gain.linearRampToValueAtTime(peakGain, startAt + 0.012);
        gain.gain.exponentialRampToValueAtTime(0.0001, startAt + duration);
        osc.connect(gain).connect(audioCtx.destination);
        osc.start(startAt);
        osc.stop(startAt + duration + 0.02);
    }

    function playCorrectSound() {
        tone(880, 0, 0.11, "sine", 0.11);
        tone(1318.5, 0.08, 0.16, "sine", 0.09);
    }

    function playWrongSound() {
        tone(196, 0, 0.22, "sawtooth", 0.07);
    }

    function playSummarySound(cls) {
        if (cls === "correct") {
            [880, 1108.7, 1318.5].forEach(function (f, i) { tone(f, i * 0.09, 0.16, "sine", 0.1); });
        } else if (cls === "wrong") {
            tone(220, 0, 0.16, "sawtooth", 0.07);
            tone(164.8, 0.14, 0.28, "sawtooth", 0.07);
        }
    }

    function vibrate(pattern) {
        if (muted || !navigator.vibrate) return;
        navigator.vibrate(pattern);
    }

    // ---------- formatting helpers (ported from the market-chart reference) ----------

    function formatMoney(value, compact) {
        var digits = compact ? 2 : value >= 1000 ? 2 : value >= 1 ? 2 : 4;
        return new Intl.NumberFormat("en-US", {
            style: "currency",
            currency: "USD",
            notation: compact ? "compact" : "standard",
            minimumFractionDigits: compact ? 0 : digits,
            maximumFractionDigits: digits,
        }).format(value);
    }

    function formatAxisPrice(value) {
        if (Math.abs(value) >= 10000) return formatMoney(value, true);
        return formatMoney(value, false);
    }

    function formatSignedPct(value) {
        var sign = value > 0 ? "+" : value < 0 ? "−" : "";
        return sign + Math.abs(value).toFixed(2) + "%";
    }

    // ---------- chart ----------

    var chart = {
        svg: null,
        candles: [],
        hoverIndex: null,
        drawnCount: 0,
        asset: "BTCUSDT",
        baseTime: 0,
        revealMarkerIndex: null,
        // Progressive hints, unlocked by misses on this chart. Null until the server sends any.
        hints: null,
    };

    /* Says out loud what a miss just unlocked, newest rung first. Shared wording with the
       daily tab, since it is the same ladder. */
    function announceHints(hints) {
        var node = document.getElementById("game-hint");
        if (!node) return;
        var lines = [];
        if (hints && hints.patternId) {
            var name = window.CandlePatterns && window.CandlePatterns.nameOf
                ? window.CandlePatterns.nameOf(hints.patternId) : hints.patternId;
            lines.push("mẫu nến gần đây: " + name);
        }
        if (hints && hints.movingAverage) lines.push("đường trung bình 5 nến");
        if (hints && hints.volumes) lines.push("khối lượng");

        node.classList.toggle("hidden", lines.length === 0);
        if (lines.length) node.textContent = "Gợi ý đã mở — " + lines.join(" · ");
    }

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        return node;
    }

    /* TradingView names intervals its own way. Keyed by our timeframe strings so adding a
       new one is a line here rather than a bug at the link. */
    var TRADINGVIEW_INTERVAL = { "15m": "15", "1h": "60", "4h": "240", "1d": "1D" };

    function formatDateTime(iso) {
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + "/" + d.getFullYear()
            + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    function formatDayHour(iso) {
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " "
            + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    function formatClock(epochSeconds) {
        var d = new Date(epochSeconds * 1000);
        var hh = String(d.getHours()).padStart(2, "0");
        var mm = String(d.getMinutes()).padStart(2, "0");
        return hh + ":" + mm;
    }

    function initChart() {
        chart.svg = svgEl("svg", {
            viewBox: "0 0 " + W + " " + H,
            preserveAspectRatio: "none",
            role: "img",
            tabindex: "0",
        });
        el.chart.appendChild(chart.svg);

        chart.svg.addEventListener("pointermove", onPointerMove);
        chart.svg.addEventListener("pointerleave", onPointerLeave);
        chart.svg.addEventListener("pointerdown", onPointerMove);
    }

    /* Which candle the pointer is over, in the svg's own units.
     *
     * These charts are drawn with preserveAspectRatio="none", so x and y are scaled by
     * different factors and no single ratio converts a screen point into chart units. Only the
     * x axis is needed here — the hover snaps to a candle — so a width ratio is enough and
     * cheaper than inverting the full matrix. */
    function onPointerMove(evt) {
        if (!chart.frame) return;
        var box = chart.svg.getBoundingClientRect();
        if (!box.width) return;
        var x = ((evt.clientX - box.left) / box.width) * W;
        setHover(chart.frame.indexAt(x));
    }

    function onPointerLeave() {
        setHover(null);
    }

    /**
     * The hover state, drawn as a layer over a chart that is already on screen.
     *
     * It used to redraw the whole chart on every pointer move, which is a few hundred DOM
     * insertions between the mouse and the picture. Now the candles are dimmed in place — one
     * opacity per group — and the guides and badges are rebuilt from the frame draw() handed
     * back. The practice chart's hover is deliberately not CandleChart.crosshair(): that one
     * follows the pointer freely on the price axis, which is right for the trade terminal
     * where somebody is measuring a level. Here the question is "which candle is that", so
     * both guides snap to the candle and a dot marks its close.
     */
    function setHover(index) {
        if (index === chart.hoverIndex) return;
        chart.hoverIndex = index;
        chart.frame.groups.forEach(function (g, i) {
            g.style.opacity = index != null && index !== i ? "0.35" : "1";
            g.style.transition = "opacity var(--duration-fast) var(--ease-out)";
        });
        drawHover();
        updateQuote();
    }

    var HOVER_LAYER = "chart-hover";

    function drawHover() {
        var old = chart.svg.querySelector("." + HOVER_LAYER);
        if (old) chart.svg.removeChild(old);
        if (chart.hoverIndex == null || !chart.frame) return;

        var f = chart.frame;
        var c = chart.candles[chart.hoverIndex];
        var hx = f.cx(chart.hoverIndex);
        var hy = f.py(c.close);
        var g = svgEl("g", { "class": HOVER_LAYER });

        g.appendChild(svgEl("line", {
            x1: hx, x2: hx, y1: f.plotY0, y2: f.bottom,
            stroke: GRID, "stroke-opacity": "0.5", "stroke-dasharray": "3 3",
        }));
        g.appendChild(svgEl("line", {
            x1: f.plotX0, x2: f.plotX1, y1: hy, y2: hy,
            stroke: GRID, "stroke-opacity": "0.5", "stroke-dasharray": "3 3",
        }));
        g.appendChild(svgEl("circle", {
            cx: hx, cy: hy, r: 4.5, fill: SURFACE,
            stroke: c.close >= c.open ? UP : DOWN, "stroke-width": "2",
        }));

        var badgeW = PAD.right - 6;
        g.appendChild(svgEl("rect", {
            x: f.plotX1 + 3, y: hy - 9, width: badgeW, height: 18, rx: 4, fill: HOVER_BADGE_BG,
        }));
        var priceText = svgEl("text", {
            x: f.plotX1 + 3 + badgeW / 2, y: hy, "text-anchor": "middle",
            "dominant-baseline": "middle", "font-size": "10", "font-weight": "700",
            fill: HOVER_BADGE_TEXT, "font-family": "var(--mono)",
        });
        priceText.textContent = formatAxisPrice(c.close);
        g.appendChild(priceText);

        var dateLabel = formatClock(chart.baseTime + chart.hoverIndex * CANDLE_STEP_SECONDS);
        var dateW = Math.max(38, dateLabel.length * 6.5 + 12);
        var dateX = Math.min(Math.max(hx - dateW / 2, f.plotX0), f.plotX1 - dateW);
        g.appendChild(svgEl("rect", {
            x: dateX, y: H - 17, width: dateW, height: 15, rx: 3, fill: HOVER_BADGE_BG,
        }));
        var dateText = svgEl("text", {
            x: dateX + dateW / 2, y: H - 9.5, "text-anchor": "middle",
            "dominant-baseline": "middle", "font-size": "9.5", "font-weight": "700",
            fill: HOVER_BADGE_TEXT, "font-family": "var(--mono)",
        });
        dateText.textContent = dateLabel;
        g.appendChild(dateText);

        chart.svg.appendChild(g);
    }

    /**
     * The practice chart, drawn by the same module every other chart on the site uses.
     *
     * This file used to carry its own renderer — grid, axis, volume strip, moving average,
     * candles, last-price line — three hundred lines that did what candle-chart.js already
     * did, a few pixels differently. Two implementations of one picture is two places to fix
     * anything about how a candle looks, and nothing to make you fix the second.
     *
     * What stayed here is the interaction, because that genuinely differs: see setHover().
     */
    function draw() {
        var n = chart.candles.length;
        if (!n) {
            chart.svg.innerHTML = "";
            chart.frame = null;
            return;
        }

        var last = chart.candles[n - 1];
        chart.frame = window.CandleChart.draw(chart.svg, chart.candles, {
            /* Wider bodies than the small charts this module was written for: the practice
               card is the widest chart on the site and shows the fewest candles. */
            body: { min: 4, ratio: 0.55, max: 44 },
            wickWidth: "1.4",
            bodyRadius: 2.5,
            volumes: chart.hints && chart.hints.volumes,
            lines: chart.hints && chart.hints.movingAverage
                ? [{ values: chart.hints.movingAverage, color: ACCENT, width: "1.8" }]
                : [],
            /* The last close, coloured by that candle's own direction — the reader is asking
               "where is price now", and the line answering carries the same green or red the
               candle it came from does. */
            referencePrice: last.close,
            referenceColor: last.close >= last.open ? "up" : "down",
            referenceLabel: formatAxisPrice(last.close),
            // Round levels with a rule at each: this is the widest chart on the site.
            ticks: 4,
            axisFormat: formatAxisPrice,
            axisFontSize: "10.5",
            marker: chart.revealMarkerIndex != null
                ? { index: chart.revealMarkerIndex, label: "diễn biến thực tế →" }
                : null,
            // Only the candles this draw is adding rise into place.
            enterFrom: n > chart.drawnCount ? chart.drawnCount : null,
            /* The candles carry no timestamp — a date is the answer — but the window's start
               and the step between candles are known, so the axis can still say the hour. */
            timeAt: function (i) { return formatClock(chart.baseTime + i * CANDLE_STEP_SECONDS); },
            timeLabels: 6,
            timeAxisInset: 6,
            timeFontSize: "10.5",
        });
        chart.drawnCount = n;

        if (chart.hoverIndex != null && chart.hoverIndex >= n) chart.hoverIndex = null;
        /* Every draw wipes the layer, so it goes back from the remembered position rather than
           waiting for the next pointer move — otherwise a reveal leaves a chart with no
           crosshair under a stationary cursor. */
        chart.frame.groups.forEach(function (g, i) {
            if (chart.hoverIndex != null && chart.hoverIndex !== i) g.style.opacity = "0.35";
        });
        drawHover();

        updateQuote();
    }

    /**
     * The chart is role="img" and focusable, so it needs a name — without one a screen
     * reader lands on it and says only "graphic". Deliberately built from the last candle
     * rather than the hovered one: updateQuote also runs on pointer move, and a name that
     * rewrote itself under the cursor would chatter without telling the user anything they
     * could act on. The per-candle detail is already in the OHLC line.
     */
    function describeChart(meta, candles) {
        if (!chart.svg) return;
        var first = candles[0], last = candles[candles.length - 1];
        // Both halves come from the series, not from updateQuote's hover-aware `shown` —
        // passing that delta in would have let the name drift under the cursor after all.
        var delta = first.close ? ((last.close - first.close) / first.close) * 100 : 0;
        chart.svg.setAttribute("aria-label",
            "Biểu đồ nến " + meta.name + " (" + meta.symbol + "), " + candles.length
            + " nến gần nhất. Giá cuối " + formatMoney(last.close, meta.compact)
            + ", " + formatSignedPct(delta) + " so với nến đầu tiên.");
    }

    function updateQuote() {
        var candles = chart.candles;
        if (!candles.length) return;
        var meta = metaFor(chart.asset);
        var shown = chart.hoverIndex != null ? candles[chart.hoverIndex] : candles[candles.length - 1];
        var baseline = candles[0].close;
        var delta = baseline ? ((shown.close - baseline) / baseline) * 100 : 0;
        var up = delta >= 0;
        var color = up ? UP : DOWN;

        window.CandleRolling.update(el.marketPrice, formatMoney(shown.close, meta.compact));
        window.CandleRolling.update(el.marketDelta, formatSignedPct(delta));
        describeChart(meta, candles);
        el.marketDelta.style.color = color;
        el.marketDelta.style.background = "color-mix(in srgb, " + color + " 14%, transparent)";

        if (chart.hoverIndex != null) {
            el.marketOhlc.classList.add("visible");
            el.marketOhlc.innerHTML =
                "<span>O <b>" + formatMoney(shown.open, meta.compact) + "</b></span>" +
                "<span>H <b>" + formatMoney(shown.high, meta.compact) + "</b></span>" +
                "<span>L <b>" + formatMoney(shown.low, meta.compact) + "</b></span>" +
                "<span>C <b style=\"color:" + color + "\">" + formatMoney(shown.close, meta.compact) + "</b></span>";
        } else {
            el.marketOhlc.classList.remove("visible");
        }
    }

    function setChartData(candles, opts) {
        chart.candles = candles;
        if (!opts || !opts.keepDrawnCount) {
            // handled by caller resetting drawnCount before append
        }
        draw();
    }

    function resetChart(asset) {
        el.marketCard.classList.add("is-loading");
        if (chart.svg) chart.svg.setAttribute("aria-label", "Đang tải biểu đồ nến.");
        chart.asset = asset;
        chart.candles = [];
        chart.hoverIndex = null;
        chart.drawnCount = 0;
        chart.revealMarkerIndex = null;
        // A new chart is a new difficulty ladder: whatever the last one gave away is not owed.
        chart.hints = null;
        announceHints(null);
        var meta = metaFor(asset);
        el.marketSymbol.textContent = meta.symbol;
        el.marketName.textContent = meta.name;
        el.marketOhlc.classList.remove("visible");
        el.marketOhlc.innerHTML = "";
    }

    // ---------- game state ----------

    var AUTO_NEXT_CHART_DELAY_MS = 4500;

    var state = {
        asset: "BTCUSDT",
        roundToken: null,
        visibleCandles: [],
        awaitingGuess: false,
        guessNumber: 1,
        totalGuesses: 5,
        guessSeconds: 0,
        /* When this browser received the current round token. The server measures the
           deadline from when it minted that token, so counting from anything else — the
           moment the buttons light up, say — drifts by however long the reveal animation
           took, and drifts badly in a backgrounded tab where timers are throttled to a
           crawl. Counting from here keeps the clock on screen and the clock being enforced
           the same clock. */
        tokenReceivedAt: 0,
        sessionCorrect: 0,
        stats: loadStats(),
    };

    var autoNextChartTimer = null;

    function summarizeSession(correct, total) {
        var pct = correct / total;
        if (correct === total) {
            return { text: "🏆 Hoàn hảo! " + correct + "/" + total + " đúng — trực giác thị trường quá đỉnh!", cls: "correct" };
        }
        if (pct >= 0.6) {
            return { text: "🎉 Chúc mừng! " + correct + "/" + total + " đúng — phong độ rất ổn!", cls: "correct" };
        }
        if (pct >= 0.4) {
            return { text: "🙂 Tạm ổn — " + correct + "/" + total + " đúng. Thử biểu đồ tiếp theo nhé!", cls: "neutral" };
        }
        return { text: "😔 Chia buồn — chỉ " + correct + "/" + total + " đúng lần này. Gỡ lại ở biểu đồ mới nào!", cls: "wrong" };
    }

    function loadStats() {
        try {
            var raw = localStorage.getItem(STORAGE_KEY);
            if (raw) return JSON.parse(raw);
        } catch (e) {
            // ignore corrupt storage
        }
        return { score: 0, streak: 0, bestStreak: 0, correct: 0, total: 0 };
    }

    function saveStats() {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state.stats));
    }

    /* ---- account stats ------------------------------------------------------------------
       Signed out, the scoreboard is whatever this browser has tallied. Signed in, the server
       owns the numbers — it is the only side that can be trusted for a ranking, and having
       two tallies would guarantee they drift apart. */

    function applyServerStats(data) {
        state.stats = {
            score: data.score,
            streak: data.currentStreak,
            bestStreak: data.bestStreak,
            correct: data.correct,
            total: data.total,
        };
        renderStats();
        renderDayStreak(data.dayStreak);
        publishStats(data);
    }

    /* /api/stats/me answers several questions at once — totals, badges, recent calls — and
       this tab is already asking it after every recorded guess. Anything else that needs one
       of those answers listens rather than asking again: two callers reading the same figure
       out of two responses can only end up disagreeing about it, and the second request buys
       nothing. Null means signed out, which is a state the listeners have to draw too. */
    function publishStats(data) {
        document.dispatchEvent(new CustomEvent("candles:stats", { detail: data }));
    }

    /* Days in a row the player has shown up — the habit number, kept out of the scoreboard on
       purpose. That grid already has a "Streak" (correct calls in a row), and two different
       numbers under the same word in one place is how a scoreboard stops being read.

       It lives in the topbar rather than on the game tab, so a player who is reading the blog
       or working through the archive can still see the run they are protecting. Everything
       that made it worth putting on the game tab still holds: it is refreshed after every
       recorded guess, so the day's first round ticks it up in front of the player.

       Hidden signed out and at zero alike: nothing is recorded for anonymous play, so a streak
       there would be a promise that vanishes the moment they sign in, and a player with no run
       going has nothing to protect. */
    function renderDayStreak(dayStreak) {
        var running = dayStreak && dayStreak.current > 0;
        el.gameStreak.classList.toggle("hidden", !running);
        if (!running) return;

        el.gameStreakValue.textContent = dayStreak.current;
        // Alive but unplayed today is the one state worth drawing differently: it is the only
        // moment when playing a round changes the number rather than just maintaining it.
        el.gameStreak.classList.toggle("is-at-risk", !dayStreak.playedToday);
        el.gameStreak.title = dayStreak.playedToday
            ? "Chuỗi " + dayStreak.current + " ngày · đã chơi hôm nay"
            : "Chuỗi " + dayStreak.current + " ngày · chơi một ván hôm nay để giữ";
    }

    /* Runs once per sign-in. The import endpoint takes the browser's tally the first time and
       is a plain read afterwards, so one call covers both the carry-over and the refresh.

       The tally is offered to one account only. The server already refuses a second import
       per account, but that does not stop the same browser handing the same history to a
       second wallet — so once it has been offered anywhere, this marks it spent. */
    async function adoptAccountStats() {
        var alreadyOffered = localStorage.getItem(STATS_CARRIED_KEY) === "1";
        var local = loadStats();
        try {
            var res;
            if (alreadyOffered) {
                res = await window.CandleAuth.authFetch("/api/stats/me");
            } else {
                res = await window.CandleAuth.authFetch("/api/stats/me/legacy", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        total: local.total, correct: local.correct,
                        score: local.score, bestStreak: local.bestStreak,
                    }),
                });
                if (res.ok) {
                    try {
                        localStorage.setItem(STATS_CARRIED_KEY, "1");
                    } catch (e) {
                        // Storage full or blocked; the server-side once-per-account rule
                        // still holds, this only loses the cross-account guard.
                    }
                }
            }
            if (res.ok) applyServerStats(await res.json());
        } catch (e) {
            // Offline or the call failed: leave the local numbers on screen rather than
            // blanking a scoreboard the player was already looking at.
        }
    }

    async function refreshAccountStats() {
        try {
            var res = await window.CandleAuth.authFetch("/api/stats/me");
            if (res.ok) applyServerStats(await res.json());
        } catch (e) {
            // Same reasoning as above.
        }
    }

    document.addEventListener("candles:session", function (event) {
        if (event.detail.user) {
            adoptAccountStats();
        } else {
            // Back to this browser's own tally, which was never overwritten. The day streak
            // goes with the account: the local tally has no dates in it to rebuild one from.
            state.stats = loadStats();
            renderStats();
            renderDayStreak(null);
            publishStats(null);
        }
    });

    function renderStats() {
        var s = state.stats;
        window.CandleRolling.update(el.score, s.score);
        window.CandleRolling.update(el.streak, s.streak);
        window.CandleRolling.update(el.bestStreak, s.bestStreak);
        window.CandleRolling.update(el.accuracy, s.total === 0 ? "–" : Math.round((s.correct / s.total) * 100) + "%");
    }

    function setStatus(text) {
        el.status.textContent = text;
    }

    function sleep(ms) {
        return new Promise(function (resolve) { setTimeout(resolve, ms); });
    }

    async function loadRound() {
        clearTimeout(autoNextChartTimer);
        autoNextChartTimer = null;
        stopGuessTimer();
        state.awaitingGuess = false;
        state.roundToken = null;
        state.guessNumber = 1;
        state.sessionCorrect = 0;
        el.guessLong.disabled = true;
        el.guessShort.disabled = true;
        el.nextChart.disabled = true;
        el.resultBanner.classList.add("hidden");
        el.roundIdentity.classList.add("hidden");
        el.roundContext.classList.add("hidden");
        setStatus("Đang tải biểu đồ mới…");
        resetChart(state.asset);

        try {
            // authFetch, not fetch: signed in, the guess gets recorded against the account;
            // signed out, it is an ordinary request and play carries on unchanged.
            var res = await window.CandleAuth.authFetch(
                "/api/practice/round?asset=" + encodeURIComponent(state.asset));
            if (!res.ok) throw new Error((await res.json()).message || "Không tải được vòng chơi");
            var data = await res.json();

            state.roundToken = data.roundToken;
            state.visibleCandles = data.candles;
            state.totalGuesses = data.totalGuesses;
            state.guessSeconds = data.guessSeconds;
            state.tokenReceivedAt = Date.now();
            // Set from the data rather than a table: the old hard-coded flag said the same
            // thing about the same four pairs, and this keeps holding for pairs added later.
            metaFor(state.asset).compact = data.candles[data.candles.length - 1].close >= 10000;
            chart.baseTime = Math.floor(Date.now() / 1000) - data.candles.length * CANDLE_STEP_SECONDS;
            el.marketCard.classList.remove("is-loading");
            setChartData(data.candles.slice());

            el.guessLong.disabled = false;
            el.guessShort.disabled = false;
            el.nextChart.disabled = false;
            el.nextChart.classList.remove("hidden");
            el.guessProgress.classList.remove("hidden");
            el.guessProgress.textContent = "Nến 1 / " + state.totalGuesses;
            state.awaitingGuess = true;
            setStatus("Nến tiếp theo sẽ là Long hay Short?");
            startGuessTimer();
        } catch (err) {
            el.marketCard.classList.remove("is-loading");
            setStatus("Lỗi: " + err.message);
        }
    }

    /**
     * Simulates the answer candle forming tick-by-tick instead of popping in instantly.
     * Walks a randomized path from open to close that visits the real high/low along the
     * way, then snaps to the exact values.
     */
    async function animateCandleReveal(candle) {
        var open = candle.open, high = candle.high, low = candle.low, close = candle.close;
        var stepsPerSegment = 9;
        var stepDelayMs = 35;

        var extremesOrder = Math.random() < 0.5 ? [high, low] : [low, high];
        var anchors = [open, extremesOrder[0], extremesOrder[1], close];

        var runningHigh = open, runningLow = open;
        var base = state.visibleCandles.map(function (c) { return { open: c.open, high: c.high, low: c.low, close: c.close }; });

        function pushFrame(o, h, l, c) {
            chart.candles = base.concat([{ open: o, high: h, low: l, close: c }]);
            draw();
        }

        pushFrame(open, open, open, open);
        await sleep(stepDelayMs);

        for (var seg = 0; seg < anchors.length - 1; seg++) {
            var from = anchors[seg], to = anchors[seg + 1];
            for (var s = 1; s <= stepsPerSegment; s++) {
                var t = s / stepsPerSegment;
                var isLast = seg === anchors.length - 2 && s === stepsPerSegment;
                var noise = isLast ? 0 : (Math.random() - 0.5) * Math.abs(to - from) * 0.2;
                var price = from + (to - from) * t + noise;
                price = Math.min(Math.max(price, low), high);
                runningHigh = Math.max(runningHigh, price);
                runningLow = Math.min(runningLow, price);
                pushFrame(open, runningHigh, runningLow, price);
                await sleep(stepDelayMs);
            }
        }

        pushFrame(open, high, low, close);
    }

    /**
     * After the last guess, reveals a few more real candles beyond the answer — purely to
     * satisfy curiosity about where the chart actually went next. No guessing involved, just
     * a quick staggered pop-in so it reads as "here's what really happened" rather than noise.
     */
    async function revealBonusCandles(candles) {
        chart.revealMarkerIndex = state.visibleCandles.length;
        for (var i = 0; i < candles.length; i++) {
            state.visibleCandles = state.visibleCandles.concat([candles[i]]);
            setChartData(state.visibleCandles.slice());
            await sleep(220);
        }
    }

    var RC = { w: 1000, h: 200, pad: { top: 10, right: 52, bottom: 20, left: 6 } };

    /* Where the context chart put things, so hovering a pattern chip can light up the right
       candles without redrawing the whole chart. */
    var rcGeom = null;

    /**
     * Draws the context chart: the same round again, but with the candles either side that
     * the player never saw, and with real dates on the axis.
     *
     * Its own renderer rather than a second instance of the game chart — no hover, no
     * animation, no rolling quote, and two shaded regions the game chart has no concept of.
     * The parts that are genuinely shared (scale, tick picking, price formatting) are reused
     * as-is; only the drawing differs.
     */
    /**
     * The end-of-round context chart: the window the player saw, the candles they had to call,
     * and the run-up and aftermath either side.
     *
     * Drawn by CandleChart like everything else. It was the third renderer in this file — the
     * practice chart, this one, and a copy of the geometry in each — and the three agreed on
     * what a candle looks like only for as long as nobody edited one of them.
     */
    function drawContextChart(context) {
        var candles = context.candles;
        var n = candles.length;
        if (!n) return;

        var svg = svgEl("svg", {
            viewBox: "0 0 " + RC.w + " " + RC.h,
            preserveAspectRatio: "none",
            role: "img",
            "aria-label": "Biểu đồ bối cảnh: " + n + " nến quanh vòng chơi vừa rồi.",
        });
        el.rcChart.innerHTML = "";
        el.rcChart.appendChild(svg);

        var frame = window.CandleChart.draw(svg, candles, {
            body: { min: 2, ratio: 0.6, max: 18 },
            bodyRadius: 1.5,
            ticks: 4,
            axisFormat: formatAxisPrice,
            axisFontSize: "10",
            timeLabels: 5,
            timeAxisInset: 5,
            timeFontSize: "9.5",
            /* Two bands behind the candles: the stretch the player was shown, and inside it
               the candles they had to call. Painted before the candles, which is what makes
               them read as ground rather than as marks on the chart. */
            highlights: [
                { from: context.playedFrom, length: context.guessFrom - context.playedFrom,
                  color: GRID, opacity: "0.09" },
                { from: context.guessFrom, length: context.guessCount,
                  color: ACCENT, opacity: "0.14" },
            ],
            // The moment guessing started, which is what the whole chart is arranged around.
            marker: { index: context.guessFrom },
        });
        if (!frame) return;

        /* Outside the played window the candles are context, not the puzzle — dimmed so the
           eye lands on the stretch the player actually saw. */
        frame.groups.forEach(function (g, i) {
            if (i < context.playedFrom || i >= context.guessFrom + context.guessCount) {
                g.setAttribute("opacity", "0.45");
            }
        });

        // One faint tick under the closing candle of each pattern; the band comes on hover.
        (context.patterns || []).forEach(function (p) {
            var last = frame.cx(p.startIndex + p.length - 1);
            svg.appendChild(svgEl("line", {
                x1: last, x2: last, y1: frame.plotY1 + 3, y2: frame.plotY1 + 7,
                stroke: ACCENT, "stroke-width": "1.2", "stroke-opacity": "0.55",
            }));
        });

        var highlight = svgEl("rect", {
            y: frame.plotY0, height: frame.plotY1 - frame.plotY0, fill: ACCENT,
            "fill-opacity": "0.3", width: "0", x: "0", opacity: "0",
        });
        svg.appendChild(highlight);

        rcGeom = {
            highlight: highlight,
            band: function (from, count) {
                return { x: frame.plotX0 + frame.step * from, width: frame.step * count };
            },
        };
    }

    function highlightPatternBand(mark) {
        if (!rcGeom) return;
        if (!mark) {
            rcGeom.highlight.setAttribute("opacity", "0");
            return;
        }
        var band = rcGeom.band(mark.startIndex, mark.length);
        rcGeom.highlight.setAttribute("x", band.x);
        rcGeom.highlight.setAttribute("width", band.width);
        rcGeom.highlight.setAttribute("opacity", "1");
    }

    /**
     * Lists the candlestick patterns the server found in the round just played. Each chip
     * lights up its candles on hover and opens the full card in the "Mẫu Nến" tab on click —
     * the point of the feature is that the explanation is one step away from the mistake,
     * not somewhere the player has to go looking for it.
     */
    function showRoundPatterns(context) {
        var marks = context.patterns || [];
        el.rcPatternsList.innerHTML = "";
        if (!marks.length) {
            el.rcPatterns.classList.add("hidden");
            return;
        }

        marks.forEach(function (mark) {
            var last = context.candles[mark.startIndex + mark.length - 1];
            var chip = document.createElement("button");
            chip.type = "button";
            chip.className = "rc-pattern";
            chip.title = "Xem mẫu này trong tab Mẫu Nến";

            var name = document.createElement("span");
            name.textContent = window.CandlePatterns
                ? window.CandlePatterns.nameOf(mark.patternId)
                : mark.patternId;

            var at = document.createElement("span");
            at.className = "rc-pattern-at";
            at.textContent = formatDayHour(last.time);

            chip.appendChild(name);
            chip.appendChild(at);
            chip.addEventListener("pointerenter", function () { highlightPatternBand(mark); });
            chip.addEventListener("focus", function () { highlightPatternBand(mark); });
            chip.addEventListener("pointerleave", function () { highlightPatternBand(null); });
            chip.addEventListener("blur", function () { highlightPatternBand(null); });
            chip.addEventListener("click", function () {
                if (window.CandlePatterns) window.CandlePatterns.reveal(mark.patternId);
            });
            el.rcPatternsList.appendChild(chip);
        });

        el.rcPatterns.classList.remove("hidden");
    }

    /* ---- the shot clock (G6) ---------------------------------------------------------
       Deadline is wall-clock, not a count of ticks, so a backgrounded tab that throttles its
       timers still runs out on time — which is the point: the clock is what stops the player
       parking the round and going to look the chart up. The server enforces the same limit
       from the token's issue time; this is the half the player can see. */

    function post(direction) {
        return window.CandleAuth.authFetch("/api/practice/guess", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ roundToken: state.roundToken, direction: direction }),
        });
    }

    var URGENT_MS = 5000;
    var guessTimerId = null;
    var guessDeadline = 0;

    function stopGuessTimer() {
        clearInterval(guessTimerId);
        guessTimerId = null;
        el.guessTimer.classList.add("hidden");
        el.guessTimer.classList.remove("urgent");
    }

    function tickGuessTimer() {
        var total = state.guessSeconds * 1000;
        var left = Math.max(0, Math.min(total, guessDeadline - Date.now()));
        el.guessTimerFill.style.width = ((left / total) * 100).toFixed(1) + "%";
        el.guessTimerValue.textContent = Math.ceil(left / 1000) + "s";
        el.guessTimer.classList.toggle("urgent", left <= URGENT_MS);
        if (left <= 0) {
            stopGuessTimer();
            submitGuess(null); // out of time — the round moves on without an answer
        }
    }

    function startGuessTimer() {
        stopGuessTimer();
        if (!state.guessSeconds) return;
        guessDeadline = state.tokenReceivedAt + state.guessSeconds * 1000;
        el.guessTimer.classList.remove("hidden");
        tickGuessTimer();
        guessTimerId = setInterval(tickGuessTimer, 100);
    }

    /**
     * Names the chart once there is nothing left to guess: real dates plus a way to go and
     * look at the same stretch on TradingView. The server withholds this until the session
     * is complete, so it arrives as an answer rather than a label that was there all along.
     */
    function showRoundIdentity(identity) {
        if (!identity) return;
        var meta = metaFor(identity.asset);
        var interval = TRADINGVIEW_INTERVAL[identity.timeframe] || "60";
        // TradingView has no way to deep-link a date, so the link opens the right symbol and
        // timeframe and the range beside it is what the player navigates to.
        var url = "https://www.tradingview.com/chart/?symbol=BINANCE%3A"
            + encodeURIComponent(identity.asset) + "&interval=" + interval;

        el.roundIdentity.innerHTML =
            '<span class="ri-label">Biểu đồ vừa rồi</span>'
            + '<span class="ri-asset">' + meta.name + " · " + identity.timeframe + "</span>"
            + '<span class="ri-range">' + formatDateTime(identity.windowStart)
            + " → " + formatDateTime(identity.windowEnd) + "</span>"
            + '<a class="ri-link" target="_blank" rel="noopener" href="' + url + '">Mở trên TradingView ↗</a>';
        el.roundIdentity.classList.remove("hidden");
    }

    /**
     * The context chart is the answer to "so what was the setup?" — shown without being
     * asked for, because sending the player elsewhere to find out is how they stop coming
     * back to the round at all.
     */
    function showRoundContext(context) {
        if (!context || !context.candles || !context.candles.length) return;
        var before = context.playedFrom;
        var after = context.candles.length - (context.guessFrom + context.guessCount);
        el.rcSub.textContent = context.candles.length + " nến · " + before
            + " nến trước và " + after + " nến sau vòng chơi";
        drawContextChart(context);
        showRoundPatterns(context);
        el.roundContext.classList.remove("hidden");
    }

    /* Auto-advance is a convenience, not a deadline. Someone reading the dates or reaching
       for the link is mid-thought, so stop the countdown and let them start the next chart
       themselves. */
    function cancelAutoNextChart() {
        if (!autoNextChartTimer) return;
        clearTimeout(autoNextChartTimer);
        autoNextChartTimer = null;
        setStatus("Bấm “Biểu đồ mới” khi bạn sẵn sàng.");
    }

    /* ---- not dealing to an empty chair -------------------------------------------------
       Auto-advance used to run whether or not anyone was watching: a tab left open in the
       background finished its chart, waited 4.5s, fetched another, let all five of its
       guesses expire, and started again — about a hundred and seventy recorded misses an
       hour, against the account of someone who had walked away. One session of this put 101
       timeouts into a single hour and dragged the visible timeout rate to 76%.

       The fix is to stop dealing, not to stop the clock. The countdown stays wall-clock on
       purpose — it is what stops a player parking a round and going to look the chart up —
       and a round already on screen still expires and is still recorded, because the player
       was given it. What ends here is the manufacture of rounds nobody ever saw. */

    var PAUSED_MESSAGE = "Tạm dừng — bấm “Biểu đồ mới” để chơi tiếp.";

    /* Two ways to not be watching, and both produced the same rows. The browser tab in the
       background is the obvious one; the other is switching to Blog or Mẫu Nến inside the
       app, which leaves `visibilityState` on "visible" while the game view carries `.hidden`.
       That second one is the likelier of the two — reading a few articles was enough to bank
       a dozen misses. */
    function isAway() {
        return document.visibilityState === "hidden" || el.viewGame.classList.contains("hidden");
    }

    function scheduleNextChart() {
        if (isAway()) {
            // Nobody is looking. Leave the board where it is and wait to be asked.
            setStatus(PAUSED_MESSAGE);
            return;
        }
        setStatus("Đang chuẩn bị biểu đồ mới…");
        autoNextChartTimer = setTimeout(loadRound, AUTO_NEXT_CHART_DELAY_MS);
    }

    /* Going away mid-wait: drop the pending chart rather than let it land unseen. The round
       in progress, if there is one, is deliberately left alone — it was dealt to someone who
       was there, and it expires on the server's clock either way. */
    function pauseIfAway() {
        if (!isAway() || !autoNextChartTimer) return;
        clearTimeout(autoNextChartTimer);
        autoNextChartTimer = null;
        setStatus(PAUSED_MESSAGE);
    }

    document.addEventListener("visibilitychange", pauseIfAway);
    document.addEventListener("candles:view", pauseIfAway);

    el.roundIdentity.addEventListener("pointerenter", cancelAutoNextChart);
    el.roundIdentity.addEventListener("focusin", cancelAutoNextChart);
    el.roundContext.addEventListener("pointerenter", cancelAutoNextChart);

    /** @param direction "LONG" / "SHORT", or null when the countdown ran out. */
    async function submitGuess(direction) {
        if (!state.awaitingGuess || !state.roundToken) return;
        state.awaitingGuess = false;
        stopGuessTimer();
        el.guessLong.disabled = true;
        el.guessShort.disabled = true;
        el.nextChart.disabled = true;
        setStatus(direction ? "Đang chấm điểm…" : "Hết giờ — đang mở nến…");

        try {
            var res = await post(direction);
            /*
             * 408 means the server's clock says the deadline passed while this was in flight.
             * Resending it as a timeout is the only move that gets the round unstuck — the
             * same request will be refused for as long as it stays an answer.
             */
            if (res.status === 408 && direction) {
                direction = null;
                res = await post(null);
            }
            if (!res.ok) throw new Error((await res.json()).message || "Không gửi được kết quả");
            var result = await res.json();
            // Before the reveal animation, not after: the server started the next token's
            // clock when it wrote this response.
            var tokenArrivedAt = Date.now();

            /* Set before the reveal, not after: the animation is what redraws the chart, so
               assigning these afterwards left every hint showing up a guess late — unlocked in
               the text, invisible on the chart until the next candle. The series are sized for
               the chart including the candle about to appear, and the drawing code ignores any
               entry past the candles it has. */
            chart.hints = result.hints;

            setStatus("Nến đang hình thành…");
            await animateCandleReveal(result.actualCandle);

            state.visibleCandles = state.visibleCandles.concat([result.actualCandle]);
            state.sessionCorrect += result.correct ? 1 : 0;
            updateStatsAfterGuess(result.correct);

            if (result.correct) {
                playCorrectSound();
                vibrate(30);
            } else {
                playWrongSound();
                vibrate([25, 40, 25]);
            }
            var missedIt = !direction;

            if (result.sessionComplete) {
                var summary = summarizeSession(state.sessionCorrect, result.totalGuesses);
                el.resultBanner.textContent = summary.text;
                el.resultBanner.className = "result-banner summary " + summary.cls;
                el.resultBanner.classList.remove("hidden");
                playSummarySound(summary.cls);
                el.guessProgress.textContent = "Hoàn thành " + result.totalGuesses + " / " + result.totalGuesses + " nến";
                el.nextChart.disabled = false;

                if (result.revealCandles && result.revealCandles.length) {
                    setStatus("Xem tiếp diễn biến giá thực tế sau đó…");
                    await revealBonusCandles(result.revealCandles);
                }

                showRoundIdentity(result.identity);
                showRoundContext(result.context);

                scheduleNextChart();
            } else {
                state.roundToken = result.nextRoundToken;
                state.tokenReceivedAt = tokenArrivedAt;
                state.guessNumber = result.guessNumber + 1;
                announceHints(result.hints);
                el.guessProgress.textContent = "Nến " + state.guessNumber + " / " + result.totalGuesses;

                el.resultBanner.textContent =
                    (missedIt ? "⏱ Hết giờ. " : result.correct ? "✅ Đúng rồi! " : "❌ Sai rồi. ") +
                    "Nến thực tế là " + result.actualDirection;
                el.resultBanner.className = "result-banner " + (result.correct ? "correct" : "wrong");
                el.resultBanner.classList.remove("hidden");

                setStatus("Nến tiếp theo sẽ là Long hay Short?");
                state.awaitingGuess = true;
                el.guessLong.disabled = false;
                el.guessShort.disabled = false;
                el.nextChart.disabled = false;
                startGuessTimer();
            }
        } catch (err) {
            // No new clock: whatever went wrong, the player has already spent this one, and
            // restarting it would hand back time the server is not going to honour.
            setStatus("Lỗi: " + err.message);
            state.awaitingGuess = true;
            el.guessLong.disabled = false;
            el.guessShort.disabled = false;
            el.nextChart.disabled = false;
        }
    }

    function updateStatsAfterGuess(correct) {
        var s = state.stats;
        s.total += 1;
        if (correct) {
            s.correct += 1;
            s.streak += 1;
            s.bestStreak = Math.max(s.bestStreak, s.streak);
            s.score += 10 + Math.min(s.streak - 1, 10) * 2;
        } else {
            s.streak = 0;
        }
        saveStats();
        renderStats();
        /* Signed out there is no recorded history to read back, so this is the only thing that
           can fill the sidebar's strip of recent calls. Signed in it lands first and the
           refresh below confirms it, which is the right order: the dot appears with the reveal
           rather than a round-trip later. */
        document.dispatchEvent(new CustomEvent("candles:guess", { detail: { correct: correct } }));

        /* Signed in, the guess was recorded server-side and the authoritative totals have
           moved. The local numbers above stay maintained regardless so that signing out
           returns to an intact browser tally. */
        if (window.CandleAuth.getUser()) refreshAccountStats();
    }

    el.guessLong.addEventListener("click", function () { unlockAudio(); submitGuess("LONG"); });
    el.guessShort.addEventListener("click", function () { unlockAudio(); submitGuess("SHORT"); });
    el.nextChart.addEventListener("click", loadRound);

    el.soundToggle.addEventListener("click", function () {
        muted = !muted;
        localStorage.setItem(MUTE_STORAGE_KEY, muted ? "1" : "0");
        updateSoundToggleUi();
        if (!muted) unlockAudio();
    });

    el.assetButtons.forEach(function (btn) {
        btn.addEventListener("click", function () {
            if (btn.dataset.asset === state.asset) return;
            el.assetButtons.forEach(function (b) { b.classList.toggle("active", b === btn); });
            state.asset = btn.dataset.asset;
            loadRound();
        });
    });

    /**
     * Replaces the markup's hard-coded pairs with the ones the server currently offers. The
     * buttons in index.html stay as the fallback: they render before this resolves, and they
     * are what remains if it fails.
     */
    async function loadAssetPicker() {
        var pill = document.getElementById("asset-pill");
        try {
            var res = await fetch("/api/assets");
            if (!res.ok) return;
            var assets = await res.json();
            if (!assets.length) return;

            var known = assets.some(function (a) { return a.symbol === state.asset; });
            if (!known) state.asset = assets[0].symbol;

            pill.innerHTML = "";
            assets.forEach(function (asset) {
                ASSET_META[asset.symbol] = ASSET_META[asset.symbol] || {
                    symbol: asset.shortSymbol,
                    name: asset.name,
                    // A pair whose price runs into the tens of thousands reads better compact;
                    // decided per chart in updateQuote once real prices are in hand.
                    compact: false,
                };
                var btn = document.createElement("button");
                btn.className = "pill-option" + (asset.symbol === state.asset ? " active" : "");
                btn.dataset.asset = asset.symbol;
                btn.textContent = asset.shortSymbol + "/USDT";
                pill.appendChild(btn);
            });

            el.assetButtons = Array.prototype.slice.call(pill.querySelectorAll(".pill-option"));
            el.assetButtons.forEach(function (btn) {
                btn.addEventListener("click", function () {
                    if (btn.dataset.asset === state.asset) return;
                    el.assetButtons.forEach(function (b) { b.classList.toggle("active", b === btn); });
                    state.asset = btn.dataset.asset;
                    loadRound();
                });
            });
        } catch (e) {
            // Offline: the markup's own buttons are already wired and still work.
        } finally {
            window.CandlePill.attach(pill, ".pill-option");
        }
    }

    initChart();
    renderStats();
    updateSoundToggleUi();
    loadAssetPicker().then(loadRound);
})();
