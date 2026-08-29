(function () {
    "use strict";

    var STORAGE_KEY = "candleGuess.stats.v1";
    var MUTE_STORAGE_KEY = "candleGuess.muted.v1";
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

    var ASSET_META = {
        BTCUSDT: { symbol: "BTC", name: "Bitcoin", compact: true },
        ETHUSDT: { symbol: "ETH", name: "Ethereum", compact: false },
        BNBUSDT: { symbol: "BNB", name: "BNB", compact: false },
        SOLUSDT: { symbol: "SOL", name: "Solana", compact: false },
    };

    var el = {
        chart: document.getElementById("chart"),
        guessLong: document.getElementById("guess-long"),
        guessShort: document.getElementById("guess-short"),
        nextChart: document.getElementById("next-chart"),
        guessProgress: document.getElementById("guess-progress"),
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

    function niceTicks(lo, hi, target) {
        if (!isFinite(lo) || !isFinite(hi) || hi <= lo) return [lo];
        var raw = (hi - lo) / (target || 4);
        var mag = Math.pow(10, Math.floor(Math.log10(raw)));
        var norm = raw / mag;
        var step = (norm >= 7.5 ? 10 : norm >= 3.5 ? 5 : norm >= 2.25 ? 2.5 : norm >= 1.5 ? 2 : 1) * mag;
        var first = Math.ceil(lo / step) * step;
        var out = [];
        for (var v = first; v <= hi + step * 0.001; v += step) out.push(Math.round(v / step) * step);
        return out;
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
    };

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        return node;
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

    function geometry(n) {
        var plotX0 = PAD.left, plotX1 = W - PAD.right;
        var plotY0 = PAD.top, plotY1 = H - PAD.bottom;
        var step = (plotX1 - plotX0) / Math.max(n, 1);
        var bodyW = Math.max(4, Math.min(step * 0.55, 44));
        return {
            plotX0: plotX0, plotX1: plotX1, plotY0: plotY0, plotY1: plotY1,
            plotW: plotX1 - plotX0, plotH: plotY1 - plotY0,
            step: step, bodyW: bodyW,
            cx: function (i) { return plotX0 + step * (i + 0.5); },
        };
    }

    function domain(candles) {
        var lo = Infinity, hi = -Infinity;
        for (var i = 0; i < candles.length; i++) {
            if (candles[i].low < lo) lo = candles[i].low;
            if (candles[i].high > hi) hi = candles[i].high;
        }
        var pad = (hi - lo) * 0.15 || Math.abs(hi) * 0.02 || 1;
        return { lo: lo - pad, hi: hi + pad };
    }

    function onPointerMove(evt) {
        if (!chart.candles.length) return;
        var box = chart.svg.getBoundingClientRect();
        var x = ((evt.clientX - box.left) / box.width) * W;
        var geo = geometry(chart.candles.length);
        var i = Math.floor((x - geo.plotX0) / geo.step);
        chart.hoverIndex = Math.max(0, Math.min(chart.candles.length - 1, i));
        draw();
    }

    function onPointerLeave() {
        chart.hoverIndex = null;
        draw();
    }

    function draw() {
        var candles = chart.candles;
        var n = candles.length;
        var svg = chart.svg;
        svg.innerHTML = "";
        if (!n) return;

        var geo = geometry(n);
        var dom = domain(candles);

        function priceY(v) {
            return geo.plotY1 - ((v - dom.lo) / (dom.hi - dom.lo || 1)) * geo.plotH;
        }

        // grid lines + axis labels
        var ticks = niceTicks(dom.lo, dom.hi, 4);
        var lastClose = candles[n - 1].close;
        var lastY = priceY(lastClose);
        ticks.forEach(function (tick) {
            var y = priceY(tick);
            if (y < geo.plotY0 - 1 || y > geo.plotY1 + 1) return;
            svg.appendChild(svgEl("line", {
                x1: geo.plotX0, x2: geo.plotX1, y1: y, y2: y,
                stroke: GRID, "stroke-opacity": "0.16", "stroke-dasharray": "2 4",
            }));
            if (Math.abs(y - lastY) < 16) return;
            var label = svgEl("text", {
                x: geo.plotX1 + 8, y: y, "dominant-baseline": "middle",
                "font-size": "10.5", fill: GRID, "font-family": "var(--mono)",
            });
            label.textContent = formatAxisPrice(tick);
            svg.appendChild(label);
        });

        // candles
        var newFrom = n > chart.drawnCount ? chart.drawnCount : Infinity;
        var wantLabels = Math.max(2, Math.min(6, Math.floor(geo.plotW / 70)));
        var labelStep = Math.max(1, Math.round(n / wantLabels));
        for (var i = 0; i < n; i++) {
            var c = candles[i];
            var up = c.close >= c.open;
            var color = up ? UP : DOWN;
            var x = geo.cx(i);
            var yHigh = priceY(c.high), yLow = priceY(c.low);
            var yOpen = priceY(c.open), yClose = priceY(c.close);
            var top = Math.min(yOpen, yClose);
            var bodyH = Math.max(1.5, Math.abs(yClose - yOpen));
            var dim = chart.hoverIndex != null && chart.hoverIndex !== i;

            var group = svgEl("g", {});
            group.style.opacity = dim ? "0.35" : "1";
            group.style.transition = "opacity var(--duration-fast) var(--ease-out)";
            if (i >= newFrom) {
                group.style.transformBox = "fill-box";
                group.style.transformOrigin = "bottom center";
                group.style.animation = "candle-rise var(--duration-enter) var(--ease-out) both";
            }

            group.appendChild(svgEl("line", {
                x1: x, x2: x, y1: yHigh, y2: yLow, stroke: color, "stroke-width": "1.4",
            }));
            group.appendChild(svgEl("rect", {
                x: x - geo.bodyW / 2, y: top, width: geo.bodyW, height: bodyH,
                rx: Math.min(2.5, geo.bodyW * 0.18), fill: color,
            }));
            svg.appendChild(group);

            if (i % labelStep === 0 || i === n - 1) {
                var timeLabel = svgEl("text", {
                    x: x, y: H - 6, "text-anchor": "middle", "font-size": "10.5",
                    fill: GRID, "font-family": "var(--mono)",
                });
                timeLabel.textContent = formatClock(chart.baseTime + i * CANDLE_STEP_SECONDS);
                svg.appendChild(timeLabel);
            }
        }
        chart.drawnCount = n;

        // divider marking where guessing stopped and the "here's what happened next" reveal begins
        if (chart.revealMarkerIndex != null && chart.revealMarkerIndex > 0 && chart.revealMarkerIndex < n) {
            var markerX = geo.plotX0 + geo.step * chart.revealMarkerIndex;
            svg.appendChild(svgEl("line", {
                x1: markerX, x2: markerX, y1: geo.plotY0, y2: geo.plotY1,
                stroke: ACCENT, "stroke-opacity": "0.55", "stroke-width": "1.4", "stroke-dasharray": "5 4",
            }));
            var markerLabel = svgEl("text", {
                x: markerX + 5, y: geo.plotY0 + 10, "font-size": "9.5",
                fill: ACCENT, "font-family": "var(--mono)", "font-weight": "600",
            });
            markerLabel.textContent = "diễn biến thực tế →";
            svg.appendChild(markerLabel);
        }

        // last-price dashed line + badge
        var lastUp = candles[n - 1].close >= candles[n - 1].open;
        var lastColor = lastUp ? UP : DOWN;
        svg.appendChild(svgEl("line", {
            x1: geo.plotX0, x2: geo.plotX1, y1: lastY, y2: lastY,
            stroke: lastColor, "stroke-opacity": "0.55", "stroke-width": "1", "stroke-dasharray": "4 4",
        }));
        var badgeW = PAD.right - 6;
        svg.appendChild(svgEl("rect", {
            x: geo.plotX1 + 3, y: lastY - 9, width: badgeW, height: 18, rx: 4, fill: lastColor,
        }));
        var badgeText = svgEl("text", {
            x: geo.plotX1 + 3 + badgeW / 2, y: lastY, "text-anchor": "middle",
            "dominant-baseline": "middle", "font-size": "10", "font-weight": "600",
            fill: SURFACE, "font-family": "var(--mono)",
        });
        badgeText.textContent = formatAxisPrice(lastClose);
        svg.appendChild(badgeText);

        // hover crosshair: vertical + horizontal dashed guides, plus price/time badges
        // that read off the exact hovered value — mirrors a standard trading-chart crosshair.
        if (chart.hoverIndex != null) {
            var hc = candles[chart.hoverIndex];
            var hx = geo.cx(chart.hoverIndex);
            var hy = priceY(hc.close);

            svg.appendChild(svgEl("line", {
                x1: hx, x2: hx, y1: geo.plotY0, y2: geo.plotY1,
                stroke: GRID, "stroke-opacity": "0.5", "stroke-dasharray": "3 3",
            }));
            svg.appendChild(svgEl("line", {
                x1: geo.plotX0, x2: geo.plotX1, y1: hy, y2: hy,
                stroke: GRID, "stroke-opacity": "0.5", "stroke-dasharray": "3 3",
            }));
            svg.appendChild(svgEl("circle", {
                cx: hx, cy: hy, r: 4.5, fill: SURFACE,
                stroke: hc.close >= hc.open ? UP : DOWN, "stroke-width": "2",
            }));

            // price badge on the right axis, at the hovered y position
            var hoverBadgeW = PAD.right - 6;
            svg.appendChild(svgEl("rect", {
                x: geo.plotX1 + 3, y: hy - 9, width: hoverBadgeW, height: 18, rx: 4, fill: HOVER_BADGE_BG,
            }));
            var hoverPriceText = svgEl("text", {
                x: geo.plotX1 + 3 + hoverBadgeW / 2, y: hy, "text-anchor": "middle",
                "dominant-baseline": "middle", "font-size": "10", "font-weight": "700",
                fill: HOVER_BADGE_TEXT, "font-family": "var(--mono)",
            });
            hoverPriceText.textContent = formatAxisPrice(hc.close);
            svg.appendChild(hoverPriceText);

            // date/time badge on the bottom axis, at the hovered x position
            var dateLabel = formatClock(chart.baseTime + chart.hoverIndex * CANDLE_STEP_SECONDS);
            var dateBadgeW = Math.max(38, dateLabel.length * 6.5 + 12);
            var dateBadgeX = Math.min(Math.max(hx - dateBadgeW / 2, geo.plotX0), geo.plotX1 - dateBadgeW);
            svg.appendChild(svgEl("rect", {
                x: dateBadgeX, y: H - 17, width: dateBadgeW, height: 15, rx: 3, fill: HOVER_BADGE_BG,
            }));
            var dateText = svgEl("text", {
                x: dateBadgeX + dateBadgeW / 2, y: H - 9.5, "text-anchor": "middle",
                "dominant-baseline": "middle", "font-size": "9.5", "font-weight": "700",
                fill: HOVER_BADGE_TEXT, "font-family": "var(--mono)",
            });
            dateText.textContent = dateLabel;
            svg.appendChild(dateText);
        }

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
        var meta = ASSET_META[chart.asset];
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
        var meta = ASSET_META[asset];
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
        state.awaitingGuess = false;
        state.roundToken = null;
        state.guessNumber = 1;
        state.sessionCorrect = 0;
        el.guessLong.disabled = true;
        el.guessShort.disabled = true;
        el.nextChart.disabled = true;
        el.resultBanner.classList.add("hidden");
        setStatus("Đang tải biểu đồ mới…");
        resetChart(state.asset);

        try {
            var res = await fetch("/api/practice/round?asset=" + encodeURIComponent(state.asset));
            if (!res.ok) throw new Error((await res.json()).message || "Không tải được vòng chơi");
            var data = await res.json();

            state.roundToken = data.roundToken;
            state.visibleCandles = data.candles;
            state.totalGuesses = data.totalGuesses;
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

    async function submitGuess(direction) {
        if (!state.awaitingGuess || !state.roundToken) return;
        state.awaitingGuess = false;
        el.guessLong.disabled = true;
        el.guessShort.disabled = true;
        el.nextChart.disabled = true;
        setStatus("Đang chấm điểm…");

        try {
            var res = await fetch("/api/practice/guess", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ roundToken: state.roundToken, direction: direction }),
            });
            if (!res.ok) throw new Error((await res.json()).message || "Không gửi được kết quả");
            var result = await res.json();

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

                setStatus("Đang chuẩn bị biểu đồ mới…");
                autoNextChartTimer = setTimeout(loadRound, AUTO_NEXT_CHART_DELAY_MS);
            } else {
                state.roundToken = result.nextRoundToken;
                state.guessNumber = result.guessNumber + 1;
                el.guessProgress.textContent = "Nến " + state.guessNumber + " / " + result.totalGuesses;

                el.resultBanner.textContent = (result.correct ? "✅ Đúng rồi! " : "❌ Sai rồi. ") +
                    "Nến thực tế là " + result.actualDirection;
                el.resultBanner.className = "result-banner " + (result.correct ? "correct" : "wrong");
                el.resultBanner.classList.remove("hidden");

                setStatus("Nến tiếp theo sẽ là Long hay Short?");
                state.awaitingGuess = true;
                el.guessLong.disabled = false;
                el.guessShort.disabled = false;
                el.nextChart.disabled = false;
            }
        } catch (err) {
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

    window.CandlePill.attach(document.getElementById("asset-pill"), ".pill-option");

    initChart();
    renderStats();
    updateSoundToggleUi();
    loadRound();
})();
