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
        note: document.getElementById("trade-note"),
        signIn: document.getElementById("trade-signin"),
        body: document.getElementById("trade-body"),
        equity: document.getElementById("trade-equity"),
        cash: document.getElementById("trade-cash"),
        realised: document.getElementById("trade-realised"),
        unrealised: document.getElementById("trade-unrealised"),
        ret: document.getElementById("trade-return"),
        markets: document.getElementById("trade-markets"),
        sides: document.getElementById("trade-sides"),
        inputLabel: document.getElementById("trade-input-label"),
        amount: document.getElementById("trade-amount"),
        quick: document.getElementById("trade-quick"),
        submit: document.getElementById("trade-submit"),
        status: document.getElementById("trade-status"),
        positions: document.getElementById("trade-positions"),
        fills: document.getElementById("trade-fills"),
        resetNote: document.getElementById("trade-reset-note"),
        reset: document.getElementById("trade-reset"),
    };

    var state = null;
    var side = "BUY";
    var selected = null;
    var busy = false;

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

    function signed(node, value) {
        window.CandleRolling.update(node, usd(value));
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
        state.markets.forEach(function (market) {
            var row = document.createElement("button");
            row.type = "button";
            row.className = "trade-market" + (market.symbol === selected ? " is-selected" : "");

            var name = document.createElement("span");
            name.className = "trade-market-name";
            name.textContent = market.symbol;

            var price = document.createElement("span");
            price.className = "trade-market-price";
            // A market with no price is shown and disabled rather than hidden: it exists, the
            // feed is just quiet, and removing the row would look like it was delisted.
            price.textContent = market.price == null ? "—" : usd(Number(market.price));

            var holding = heldOf(market.symbol);
            var held = document.createElement("span");
            held.className = "trade-market-held";
            held.textContent = holding > 0 ? "giữ " + qty(holding) : "";

            row.appendChild(name);
            row.appendChild(price);
            row.appendChild(held);
            row.addEventListener("click", function () {
                selected = market.symbol;
                render();
            });
            el.markets.appendChild(row);
        });
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

        window.CandleRolling.update(el.equity, usd(state.equity));
        window.CandleRolling.update(el.cash, usd(state.cash));
        signed(el.realised, Number(state.realisedPnl));
        signed(el.unrealised, Number(state.unrealisedPnl));

        var start = Number(state.startingBalance);
        var pct = start === 0 ? 0 : ((Number(state.equity) - start) / start) * 100;
        window.CandleRolling.update(el.ret, (pct >= 0 ? "+" : "") + pct.toFixed(2) + "%");
        el.ret.classList.toggle("is-up", pct > 0);
        el.ret.classList.toggle("is-down", pct < 0);

        el.note.textContent = "phí " + (state.feeBps / 100).toFixed(2) + "% mỗi lệnh";
        el.inputLabel.textContent = side === "BUY" ? "Số tiền (USD)" : "Số lượng " + selected;
        el.submit.textContent = side === "BUY" ? "Mua " + selected : "Bán " + selected;
        el.submit.className = "guess-btn " + (side === "BUY" ? "long" : "short");
        el.submit.disabled = busy || priceOf(selected) == null;

        el.resetNote.textContent = state.resets > 0
            ? "Đã chơi lại " + state.resets + " lần. Reset xoá sạch danh mục và trả về $"
              + Number(state.startingBalance).toLocaleString("en-US") + "."
            : "Reset xoá sạch danh mục và trả về $"
              + Number(state.startingBalance).toLocaleString("en-US") + ".";

        renderMarkets();
        renderQuick();
        renderPositions();
        renderFills();
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
            if (state) el.submit.disabled = priceOf(selected) == null;
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

    el.submit.addEventListener("click", submit);
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
            render();
        } catch (e) {
            el.signIn.textContent = "Không tải được danh mục: " + e.message;
            el.signIn.classList.remove("hidden");
            el.body.classList.add("hidden");
        }
    }

    window.__initTradeView = load;
})();
