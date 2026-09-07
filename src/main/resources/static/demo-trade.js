/* The paper-trading terminal.
 *
 * Every number on screen comes from /api/demo/portfolio and none of them are computed here —
 * the server folds the account out of its own trade log, and a client that did its own
 * arithmetic would eventually disagree with it about how much money someone has.
 *
 * The whole surface needs an account, so the signed-out state is a prompt rather than an error:
 * a 401 here is the expected answer to "show me a portfolio" from someone who has none. */
(function () {
    "use strict";

    var el = {
        signIn: document.getElementById("trade-signin"),
        body: document.getElementById("trade-body"),
        equity: document.getElementById("trade-equity"),
        cash: document.getElementById("trade-cash"),
        realised: document.getElementById("trade-realised"),
        unrealised: document.getElementById("trade-unrealised"),
        ret: document.getElementById("trade-return"),
        search: document.getElementById("trade-search"),
        markets: document.getElementById("trade-markets"),
        pair: document.getElementById("trade-pair"),
        pairName: document.getElementById("trade-pair-name"),
        statPrice: document.getElementById("trade-stat-price"),
        statChange: document.getElementById("trade-stat-change"),
        statVolume: document.getElementById("trade-stat-volume"),
        statHeld: document.getElementById("trade-stat-held"),
        chart: document.getElementById("trade-chart"),
        indicators: document.getElementById("trade-indicators"),
        rsiPane: document.getElementById("trade-rsi-pane"),
        rsi: document.getElementById("trade-rsi"),
        timeframes: document.getElementById("trade-timeframes"),
        panBack: document.getElementById("trade-pan-back"),
        panForward: document.getElementById("trade-pan-forward"),
        zoomIn: document.getElementById("trade-zoom-in"),
        zoomOut: document.getElementById("trade-zoom-out"),
        zoomCount: document.getElementById("trade-zoom-count"),
        preview: document.getElementById("trade-preview"),
        positionCard: document.getElementById("trade-position-card"),
        sides: document.getElementById("trade-sides"),
        inputLabel: document.getElementById("trade-input-label"),
        amount: document.getElementById("trade-amount"),
        quick: document.getElementById("trade-quick"),
        submit: document.getElementById("trade-submit"),
        status: document.getElementById("trade-status"),
        positions: document.getElementById("trade-positions"),
        fills: document.getElementById("trade-fills"),
        reset: document.getElementById("trade-reset"),
    };

    var state = null;
    var side = "BUY";
    var selected = null;
    var busy = false;
    var filter = "";
    var timeframe = "1h";
    /* How many candles are on screen. The chart scales to whatever it is given, so fewer bars
       is more detail — "zoom in" walks this down, not up. Steps rather than a free number so
       each press is a visible change; a linear step would do nothing at the wide end. */
    var ZOOM_STEPS = [40, 60, 90, 120, 180, 260, 400];
    var zoomStep = 3;
    /* How many candles back from the newest the right edge of the view sits. 0 is the live
       edge, which is where every load and every market or timeframe switch puts it — someone
       opening a chart wants the price now, not wherever they last dragged to. */
    var panOffset = 0;
    var showMa = false;
    var showRsi = false;

    /* Periods are fixed rather than configurable. Two averages and one oscillator is what a
       chart this size can show without becoming a settings panel, and 20/50/14 are the ones
       every other terminal defaults to — a reading is only useful if it means the same thing
       here as everywhere else. */
    var MA_PERIODS = [20, 50];
    var RSI_PERIOD = 14;
    /* Keyed by symbol *and* timeframe, so switching back to something already looked at draws
       instantly instead of blanking the chart while a request goes out. One key per view is what
       stops a 4h chart being served the 1h candles that happen to be cached for that symbol. */
    var chartCache = {};

    /* Money is shown to the cent and quantities to as much precision as they need. A holding of
       0.0003 BTC rounded to two places would read as nothing at all. */
    function usd(v) {
        if (v == null) return "–";
        return (v < 0 ? "-$" : "$") + Math.abs(v).toLocaleString("en-US",
            { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function qty(v) {
        if (v == null) return "–";
        return Number(v).toLocaleString("en-US", { maximumFractionDigits: 8 });
    }

    /* Volume is read as a size, not a figure to reconcile — "54.0M" is the useful precision. */
    function compact(v) {
        if (v == null) return "—";
        var n = Number(v);
        if (n >= 1e9) return "$" + (n / 1e9).toFixed(1) + "B";
        if (n >= 1e6) return "$" + (n / 1e6).toFixed(1) + "M";
        if (n >= 1e3) return "$" + (n / 1e3).toFixed(1) + "K";
        return usd(n);
    }

    function pct(v) {
        if (v == null) return "—";
        var n = Number(v);
        return (n >= 0 ? "+" : "") + n.toFixed(2) + "%";
    }

    function tone(node, value) {
        node.classList.toggle("is-up", value > 0);
        node.classList.toggle("is-down", value < 0);
    }

    /* Written as text rather than through CandleRolling. The odometer animates a strip of
       digits and needs the .rolling class to clip it; these values carry currency symbols and
       separators and change on every poll, so a plain write is both correct and calmer. */
    function signed(node, value) {
        node.textContent = (value > 0 ? "+" : "") + usd(value);
        node.classList.toggle("is-up", value > 0);
        node.classList.toggle("is-down", value < 0);
    }

    function heldOf(symbol) {
        var found = (state.positions || []).filter(function (p) { return p.symbol === symbol; })[0];
        return found ? Number(found.quantity) : 0;
    }

    function priceOf(symbol) {
        var found = (state.markets || []).filter(function (m) { return m.symbol === symbol; })[0];
        return found && found.price != null ? Number(found.price) : null;
    }

    function renderMarkets() {
        el.markets.innerHTML = "";
        var needle = filter.trim().toUpperCase();
        var shown = state.markets.filter(function (m) {
            return !needle || m.symbol.indexOf(needle) >= 0
                || (m.name || "").toUpperCase().indexOf(needle) >= 0;
        });

        if (!shown.length) {
            el.markets.innerHTML = '<p class="profile-empty">Không có cặp nào khớp.</p>';
            return;
        }

        shown.forEach(function (market) {
            var row = document.createElement("button");
            row.type = "button";
            row.className = "trade-market" + (market.symbol === selected ? " is-selected" : "");

            var name = document.createElement("span");
            name.className = "trade-market-name";
            name.textContent = market.symbol;

            var sub = document.createElement("span");
            sub.className = "trade-market-sub";
            var holding = heldOf(market.symbol);
            // The holding is the one thing about a market that is personal, so it replaces the
            // generic subtitle when there is one — it is what the player is looking for.
            sub.textContent = holding > 0 ? "giữ " + qty(holding) : (market.name || "");

            var price = document.createElement("span");
            price.className = "trade-market-price";
            // A market with no price is shown and dimmed rather than hidden: it exists, the
            // feed is just quiet, and removing the row would look like a delisting.
            price.textContent = market.price == null ? "—" : usd(Number(market.price));

            var change = document.createElement("span");
            change.className = "trade-market-change";
            change.textContent = pct(market.change24h);
            if (market.change24h != null) tone(change, Number(market.change24h));

            row.appendChild(name);
            row.appendChild(price);
            row.appendChild(sub);
            row.appendChild(change);
            row.addEventListener("click", function () { select(market.symbol); });
            el.markets.appendChild(row);
        });
    }

    function marketOf(symbol) {
        return (state.markets || []).filter(function (m) { return m.symbol === symbol; })[0];
    }

    function renderHeadline() {
        var market = marketOf(selected);
        if (!market) return;

        el.pair.textContent = market.symbol;
        el.pairName.textContent = market.name || "";
        el.statPrice.textContent = market.price == null ? "—" : usd(Number(market.price));
        el.statChange.textContent = pct(market.change24h);
        if (market.change24h != null) tone(el.statChange, Number(market.change24h));
        el.statVolume.textContent = compact(market.volume24h);

        var holding = heldOf(selected);
        el.statHeld.textContent = holding > 0 ? qty(holding) : "—";
    }

    function chartKey() {
        return selected + "@" + timeframe;
    }

    /* Both indicators are computed over the whole fetched series and sliced with the candles,
       never over the visible window alone. A moving average is a property of a candle within
       the series, not of the view: computing per-window would leave the left edge blank and,
       worse, change the value shown for the same candle as soon as anyone panned. */
    function movingAverage(closes, period) {
        var out = new Array(closes.length).fill(null);
        var sum = 0;
        for (var i = 0; i < closes.length; i++) {
            sum += closes[i];
            if (i >= period) sum -= closes[i - period];
            if (i >= period - 1) out[i] = sum / period;
        }
        return out;
    }

    /* Wilder's RSI — the smoothing every charting tool uses, not a plain average of the last
       fourteen changes, which drifts away from what other terminals show for the same candles. */
    function relativeStrength(closes, period) {
        var out = new Array(closes.length).fill(null);
        if (closes.length <= period) return out;

        var gain = 0, loss = 0;
        for (var i = 1; i <= period; i++) {
            var d = closes[i] - closes[i - 1];
            if (d >= 0) gain += d; else loss -= d;
        }
        gain /= period;
        loss /= period;
        // No down move in the window means no ratio to take; the reading is pinned at 100.
        out[period] = loss === 0 ? 100 : 100 - 100 / (1 + gain / loss);

        for (var j = period + 1; j < closes.length; j++) {
            var change = closes[j] - closes[j - 1];
            gain = (gain * (period - 1) + Math.max(change, 0)) / period;
            loss = (loss * (period - 1) + Math.max(-change, 0)) / period;
            out[j] = loss === 0 ? 100 : 100 - 100 / (1 + gain / loss);
        }
        return out;
    }

    function visibleCount() {
        var loaded = (chartCache[chartKey()] || []).length;
        return Math.min(ZOOM_STEPS[zoomStep], loaded);
    }

    /** How far back the view can go before it runs out of candles. */
    function maxPan() {
        var loaded = (chartCache[chartKey()] || []).length;
        return Math.max(0, loaded - visibleCount());
    }

    function setPan(next) {
        var clamped = Math.min(Math.max(Math.round(next), 0), maxPan());
        if (clamped === panOffset) return false;
        panOffset = clamped;
        return true;
    }

    function renderZoom() {
        var loaded = (chartCache[chartKey()] || []).length;
        el.zoomIn.disabled = zoomStep <= 0;
        // Nothing to widen to once every candle fetched is already on screen.
        el.zoomOut.disabled = zoomStep >= ZOOM_STEPS.length - 1
            || ZOOM_STEPS[zoomStep] >= loaded;
        el.panBack.disabled = panOffset >= maxPan();
        // Disabled at the live edge, which is also how the chart says it is showing "now".
        el.panForward.disabled = panOffset <= 0;
        el.zoomCount.textContent = loaded ? visibleCount() + " nến" : "";
    }

    function drawChart() {
        var candles = chartCache[chartKey()];
        // Zooming out can leave the view hanging past the oldest candle; pull it back in first.
        setPan(panOffset);
        renderZoom();
        if (!candles || !candles.length) {
            window.CandleChart.draw(el.chart, []);
            return;
        }

        var count = visibleCount();
        var end = candles.length - panOffset;
        var from = Math.max(0, end - count);
        var slice = candles.slice(from, end);
        var atLiveEdge = panOffset === 0;

        // Computed over everything, then cut to the same window as the candles.
        var closes = candles.map(function (c) { return +c.close; });
        var overlays = showMa ? MA_PERIODS.map(function (period, i) {
            return {
                values: movingAverage(closes, period).slice(from, end),
                color: i === 0 ? "var(--accent)" : "var(--warn)",
            };
        }) : [];

        el.rsiPane.classList.toggle("hidden", !showRsi);
        if (showRsi) {
            window.CandleChart.drawIndicator(el.rsi,
                relativeStrength(closes, RSI_PERIOD).slice(from, end),
                { min: 0, max: 100, guides: [30, 70] });
        }

        var market = marketOf(selected);
        /* The live price line is only drawn while the newest candle is on screen. CandleChart
           widens its price scale to fit that line, so leaving it on a chart panned back three
           months would squash every candle into a band at one edge to make room for a price
           none of them ever traded at. */
        window.CandleChart.draw(el.chart, slice.map(function (c) {
            return { time: c.time, open: +c.open, high: +c.high, low: +c.low, close: +c.close };
        }), {
            lines: overlays,
            referencePrice: atLiveEdge && market && market.price != null
                ? Number(market.price) : null,
        });
    }

    /* Fetched per market and kept, because hourly candles do not move between two clicks and a
       chart that blanks every time you glance at another pair is worse than a slightly old one. */
    /* 1m and 15m come from the exchange and move constantly, so they are not cached the way
       the stored timeframes are — keeping a minute chart across a tab switch would show a
       picture that is quietly minutes old. */
    function isIntraday(tf) {
        return tf === "1m" || tf === "15m";
    }

    async function loadChart(symbol, tf) {
        var key = symbol + "@" + tf;
        if (chartCache[key] && !isIntraday(tf)) return;
        try {
            var res = await window.CandleAuth.authFetch(
                "/api/demo/chart?asset=" + encodeURIComponent(symbol)
                + "&tf=" + encodeURIComponent(tf) + "&limit=" + ZOOM_STEPS[ZOOM_STEPS.length - 1]);
            if (!res.ok) return;
            var payload = await res.json();
            chartCache[key] = payload.candles;
            if (key === chartKey()) drawChart();
        } catch (e) {
            // The chart is context, not the trade. Losing it must not stop anyone trading.
        }
    }

    function select(symbol) {
        selected = symbol;
        panOffset = 0;
        el.amount.value = "";
        el.status.textContent = "";
        render();
        loadChart(symbol, timeframe);
    }

    /* Quick amounts are the whole reason this is usable on a phone. Buying offers fractions of
       cash; selling offers fractions of the holding, because those are different questions. */
    function renderQuick() {
        el.quick.innerHTML = "";
        var options = side === "BUY"
            ? [["25%", state.cash * 0.25], ["50%", state.cash * 0.5], ["Tất cả", state.cash / (1 + state.feeBps / 10000)]]
            : [["25%", heldOf(selected) * 0.25], ["50%", heldOf(selected) * 0.5], ["Tất cả", heldOf(selected)]];

        options.forEach(function (option) {
            var button = document.createElement("button");
            button.type = "button";
            button.className = "trade-quick-btn";
            button.textContent = option[0];
            button.disabled = !(option[1] > 0);
            button.addEventListener("click", function () {
                // Floored to something the input can show without rounding the last cent up
                // into more cash than the account has.
                el.amount.value = side === "BUY"
                    ? (Math.floor(option[1] * 100) / 100)
                    : option[1];
            });
            el.quick.appendChild(button);
        });
    }

    /* What the order will actually do, before it is sent. A market order with a fee is not
       self-evident from the amount typed in — the difference between "spend $500" and "receive
       0.0063 BTC after $0.50 of fee" is the whole thing a player is agreeing to. */
    function renderPreview() {
        var amount = Number(el.amount.value);
        var price = priceOf(selected);
        if (!(amount > 0) || price == null) {
            el.preview.textContent = "";
            el.preview.classList.add("hidden");
            return;
        }
        el.preview.classList.remove("hidden");

        var feeRate = state.feeBps / 10000;
        if (side === "BUY") {
            el.preview.innerHTML = "";
            el.preview.appendChild(previewRow("Nhận về", qty(amount / price) + " " + selected));
            el.preview.appendChild(previewRow("Phí", usd(amount * feeRate)));
            el.preview.appendChild(previewRow("Tổng trừ", usd(amount * (1 + feeRate))));
        } else {
            var gross = amount * price;
            el.preview.innerHTML = "";
            el.preview.appendChild(previewRow("Bán", qty(amount) + " " + selected));
            el.preview.appendChild(previewRow("Phí", usd(gross * feeRate)));
            el.preview.appendChild(previewRow("Nhận về", usd(gross * (1 - feeRate))));
        }
    }

    function previewRow(label, value) {
        var row = document.createElement("div");
        row.className = "trade-preview-row";
        var l = document.createElement("span");
        l.textContent = label;
        var v = document.createElement("span");
        v.textContent = value;
        row.appendChild(l);
        row.appendChild(v);
        return row;
    }

    /* The open position in the selected market, beside the ticket rather than only in the table
       below — deciding whether to add or trim is a question about this market, asked here. */
    function renderPositionCard() {
        var position = (state.positions || []).filter(function (p) {
            return p.symbol === selected;
        })[0];

        el.positionCard.innerHTML = "";
        el.positionCard.classList.toggle("hidden", !position);
        if (!position) return;

        el.positionCard.appendChild(previewRow("Đang giữ", qty(position.quantity)));
        el.positionCard.appendChild(previewRow("Giá vốn", usd(Number(position.averageCost))));
        el.positionCard.appendChild(previewRow("Giá trị",
            position.value == null ? "—" : usd(Number(position.value))));

        var pnl = previewRow("Lãi/lỗ", position.unrealisedPnl == null ? "—"
            : (Number(position.unrealisedPnl) >= 0 ? "+" : "") + usd(Number(position.unrealisedPnl)));
        if (position.unrealisedPnl != null) tone(pnl.lastChild, Number(position.unrealisedPnl));
        el.positionCard.appendChild(pnl);
    }

    function renderPositions() {
        el.positions.innerHTML = "";
        if (!state.positions.length) {
            el.positions.innerHTML = '<p class="profile-empty">Chưa giữ gì. Chọn một cặp và mua thử.</p>';
            return;
        }
        state.positions.forEach(function (position) {
            var card = document.createElement("div");
            card.className = "profile-asset trade-position";

            var name = document.createElement("span");
            name.className = "profile-asset-name";
            name.textContent = position.symbol;

            var amount = document.createElement("span");
            amount.className = "trade-position-qty";
            amount.textContent = qty(position.quantity);

            var value = document.createElement("span");
            value.className = "profile-asset-count";
            value.textContent = position.value == null ? "—" : usd(Number(position.value));

            var pnl = document.createElement("span");
            pnl.className = "trade-position-pnl";
            if (position.unrealisedPnl != null) {
                var open = Number(position.unrealisedPnl);
                pnl.textContent = (open >= 0 ? "+" : "") + usd(open);
                pnl.classList.toggle("is-up", open > 0);
                pnl.classList.toggle("is-down", open < 0);
            } else {
                pnl.textContent = "—";
            }

            var cost = document.createElement("span");
            cost.className = "trade-position-cost";
            cost.textContent = "vốn " + usd(Number(position.averageCost));

            card.appendChild(name);
            card.appendChild(amount);
            card.appendChild(value);
            card.appendChild(pnl);
            card.appendChild(cost);
            el.positions.appendChild(card);
        });
    }

    function renderFills() {
        el.fills.innerHTML = "";
        if (!state.recent.length) {
            el.fills.innerHTML = '<p class="profile-empty">Lịch sử sẽ xuất hiện sau lệnh đầu tiên.</p>';
            return;
        }
        state.recent.forEach(function (fill) {
            var item = document.createElement("div");
            var buy = fill.side === "BUY";
            item.className = "profile-guess " + (buy ? "is-correct" : "is-wrong");

            var mark = document.createElement("span");
            mark.className = "profile-guess-mark";
            mark.textContent = buy ? "▲" : "▼";

            var symbol = document.createElement("span");
            symbol.className = "profile-guess-symbol";
            symbol.textContent = fill.symbol;

            var detail = document.createElement("span");
            detail.className = "profile-guess-call";
            detail.textContent = (buy ? "mua " : "bán ") + qty(fill.quantity)
                + " @ " + usd(Number(fill.price));

            var when = document.createElement("span");
            when.className = "profile-guess-when";
            when.textContent = new Date(fill.at).toLocaleString("vi-VN",
                { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" });

            item.appendChild(mark);
            item.appendChild(symbol);
            item.appendChild(detail);
            item.appendChild(when);
            el.fills.appendChild(item);
        });
    }

    function render() {
        if (!selected && state.markets.length) selected = state.markets[0].symbol;

        el.equity.textContent = usd(state.equity);
        el.cash.textContent = usd(state.cash);
        signed(el.realised, Number(state.realisedPnl));
        signed(el.unrealised, Number(state.unrealisedPnl));

        var start = Number(state.startingBalance);
        // Named `ret`, not `pct` — pct() is the formatter and shadowing it here would silently
        // break every percentage on the page below this line.
        var ret = start === 0 ? 0 : ((Number(state.equity) - start) / start) * 100;
        el.ret.textContent = (ret >= 0 ? "+" : "") + ret.toFixed(2) + "%";
        tone(el.ret, ret);

        el.inputLabel.textContent = side === "BUY" ? "Số tiền (USD)" : "Số lượng " + selected;
        el.submit.textContent = side === "BUY" ? "Mua " + selected : "Bán " + selected;
        el.submit.className = "guess-btn " + (side === "BUY" ? "long" : "short");
        // Selling nothing is refused by the server anyway; refusing it here says why without
        // spending a round trip to be told.
        var nothingToSell = side === "SELL" && heldOf(selected) <= 0;
        el.submit.disabled = busy || priceOf(selected) == null || nothingToSell;
        if (nothingToSell) el.status.textContent = "Chưa giữ " + selected + " nào để bán.";

        el.reset.title = state.resets > 0
            ? "Đã chơi lại " + state.resets + " lần. Reset trả về $"
              + Number(state.startingBalance).toLocaleString("en-US") + "."
            : "Reset trả về $" + Number(state.startingBalance).toLocaleString("en-US") + ".";

        renderMarkets();
        renderHeadline();
        renderQuick();
        renderPreview();
        renderPositionCard();
        renderPositions();
        renderFills();
        drawChart();
    }

    async function post(path, body) {
        busy = true;
        if (state) el.submit.disabled = true;
        try {
            var res = await window.CandleAuth.authFetch(path, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body || {}),
            });
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            state = payload;
            el.amount.value = "";
            el.status.textContent = "";
            render();
        } catch (e) {
            el.status.textContent = e.message;
        } finally {
            busy = false;
            // Re-derived rather than just re-enabled: the trade may have closed the position
            // that made selling possible in the first place.
            if (state) render();
        }
    }

    function submit() {
        var amount = Number(el.amount.value);
        if (!(amount > 0)) {
            el.status.textContent = side === "BUY"
                ? "Nhập số tiền muốn mua." : "Nhập số lượng muốn bán.";
            return;
        }
        // The server prices the fill and re-checks affordability; this only stops the obvious
        // mistakes before a round trip.
        return post("/api/demo/trade", side === "BUY"
            ? { asset: selected, side: "BUY", amountUsd: amount }
            : { asset: selected, side: "SELL", quantity: amount });
    }

    el.sides.addEventListener("click", function (event) {
        var option = event.target.closest(".pill-option");
        if (!option || option.classList.contains("active")) return;
        Array.prototype.forEach.call(el.sides.querySelectorAll(".pill-option"), function (b) {
            b.classList.toggle("active", b === option);
        });
        side = option.dataset.side;
        el.amount.value = "";
        el.status.textContent = "";
        render();
    });

    function zoom(delta) {
        var next = Math.min(Math.max(zoomStep + delta, 0), ZOOM_STEPS.length - 1);
        if (next === zoomStep) return;
        zoomStep = next;
        // Purely a redraw: the candles are already here, so no button here hits the network.
        drawChart();
    }

    /** A quarter of the window per press — enough to see it move, small enough to keep your place. */
    function pan(direction) {
        if (setPan(panOffset + direction * Math.max(1, Math.round(visibleCount() / 4)))) drawChart();
    }

    el.indicators.addEventListener("click", function (event) {
        var option = event.target.closest(".pill-option");
        if (!option) return;
        // Toggles rather than a single choice: an average and an oscillator answer different
        // questions and are read together, so the pill here is not exclusive the way the
        // timeframe one is.
        option.classList.toggle("active");
        if (option.dataset.ind === "ma") showMa = option.classList.contains("active");
        else showRsi = option.classList.contains("active");
        drawChart();
    });

    el.zoomIn.addEventListener("click", function () { zoom(-1); });
    el.zoomOut.addEventListener("click", function () { zoom(1); });
    el.panBack.addEventListener("click", function () { pan(1); });
    el.panForward.addEventListener("click", function () { pan(-1); });

    /* Dragging the chart itself. The content follows the pointer — pulling right reveals older
       candles — which is the direction every chart tool uses and the opposite of moving a
       scrollbar. Distance is converted through the chart's own width so one drag covers the
       same ground whatever the screen size or zoom level. */
    var drag = null;

    el.chart.addEventListener("pointerdown", function (event) {
        if (!chartCache[chartKey()]) return;
        drag = { x: event.clientX, from: panOffset, width: el.chart.getBoundingClientRect().width };
        el.chart.setPointerCapture(event.pointerId);
        el.chart.classList.add("is-dragging");
    });

    el.chart.addEventListener("pointermove", function (event) {
        if (!drag || !drag.width) return;
        var candlesPerPixel = visibleCount() / drag.width;
        if (setPan(drag.from + (event.clientX - drag.x) * candlesPerPixel)) drawChart();
    });

    function endDrag(event) {
        if (!drag) return;
        drag = null;
        el.chart.classList.remove("is-dragging");
        if (event && el.chart.hasPointerCapture(event.pointerId)) {
            el.chart.releasePointerCapture(event.pointerId);
        }
    }

    el.chart.addEventListener("pointerup", endDrag);
    el.chart.addEventListener("pointercancel", endDrag);

    /* Horizontal wheel and trackpad swipes. Only claimed when the gesture is more sideways than
       vertical, so scrolling the page over the chart still scrolls the page. */
    el.chart.addEventListener("wheel", function (event) {
        if (Math.abs(event.deltaX) <= Math.abs(event.deltaY)) return;
        event.preventDefault();
        if (setPan(panOffset - event.deltaX * (visibleCount() / 600))) drawChart();
    }, { passive: false });

    el.timeframes.addEventListener("click", function (event) {
        var option = event.target.closest(".pill-option");
        if (!option || option.classList.contains("active")) return;
        Array.prototype.forEach.call(el.timeframes.querySelectorAll(".pill-option"), function (b) {
            b.classList.toggle("active", b === option);
        });
        timeframe = option.dataset.tf;
        panOffset = 0;
        drawChart();
        loadChart(selected, timeframe);
    });

    el.submit.addEventListener("click", submit);
    // The preview follows the keystrokes; nothing else needs redrawing on every one.
    el.amount.addEventListener("input", renderPreview);
    el.search.addEventListener("input", function () {
        filter = el.search.value;
        renderMarkets();
    });
    el.amount.addEventListener("keydown", function (event) {
        if (event.key === "Enter") submit();
    });
    el.reset.addEventListener("click", function () {
        if (window.confirm("Xoá sạch danh mục và bắt đầu lại?")) post("/api/demo/reset");
    });

    async function load() {
        // Signed out is the expected state, not a failure: the prompt is the whole view.
        if (!window.CandleAuth.getUser()) {
            el.signIn.classList.remove("hidden");
            el.body.classList.add("hidden");
            return;
        }
        try {
            var res = await window.CandleAuth.authFetch("/api/demo/portfolio");
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            state = payload;
            el.signIn.classList.add("hidden");
            el.body.classList.remove("hidden");
            // Back to the live edge. Zoom is how much to look at and survives; pan is where you
            // were looking, and reopening the tab should show now rather than last week.
            panOffset = 0;
            if (!selected && state.markets.length) selected = state.markets[0].symbol;
            render();
            loadChart(selected, timeframe);
        } catch (e) {
            el.signIn.textContent = "Không tải được danh mục: " + e.message;
            el.signIn.classList.remove("hidden");
            el.body.classList.add("hidden");
        }
    }

    window.__initTradeView = load;
})();
