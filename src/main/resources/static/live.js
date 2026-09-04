/* The live round: one shared call on the candle the exchange is forming right now, as opposed
   to the practice tab's random historical chart. Built on first reveal (see nav.js's
   onFirstShow), not at load — same reasoning as heatmap and blog. */
(function () {
    "use strict";

    var el = {
        assetPill: document.getElementById("live-asset-pill"),
        roundNumber: document.getElementById("live-round-number"),
        roundState: document.getElementById("live-round-state"),
        symbol: document.getElementById("live-symbol"),
        price: document.getElementById("live-price"),
        delta: document.getElementById("live-delta"),
        openNote: document.getElementById("live-open-note"),
        sparkline: document.getElementById("live-sparkline"),
        timer: document.getElementById("live-timer"),
        timerFill: document.getElementById("live-timer-fill"),
        timerValue: document.getElementById("live-timer-value"),
        poolLong: document.getElementById("live-pool-long"),
        poolLongLabel: document.getElementById("live-pool-long-label"),
        poolShortLabel: document.getElementById("live-pool-short-label"),
        longBtn: document.getElementById("live-long"),
        shortBtn: document.getElementById("live-short"),
        status: document.getElementById("live-status"),
        historyStrip: document.getElementById("live-history-strip"),
        modal: document.getElementById("live-round-modal"),
        modalBackdrop: document.getElementById("live-round-modal-backdrop"),
        modalClose: document.getElementById("live-round-modal-close"),
        modalEyebrow: document.getElementById("live-round-modal-eyebrow"),
        modalTitle: document.getElementById("live-round-modal-title"),
        modalChart: document.getElementById("live-round-modal-chart"),
        modalBadgeState: document.getElementById("live-round-modal-badge-state"),
        modalBadgeResult: document.getElementById("live-round-modal-badge-result"),
        modalOpen: document.getElementById("live-round-modal-open"),
        modalClosePrice: document.getElementById("live-round-modal-close-price"),
        modalPoolLong: document.getElementById("live-round-modal-pool-long"),
        modalPoolLongLabel: document.getElementById("live-round-modal-pool-long-label"),
        modalPoolShortLabel: document.getElementById("live-round-modal-pool-short-label"),
        modalStatus: document.getElementById("live-round-modal-status"),
    };

    var SYMBOL_NAME = { BTCUSDT: "BTC", ETHUSDT: "ETH", BNBUSDT: "BNB", SOLUSDT: "SOL" };
    var POLL_MS = 3000;

    var state = {
        asset: "BTCUSDT",
        round: null,       // last snapshot from GET /api/live/round
        history: [],
    };

    var pollTimer = null;
    var tickTimer = null;

    function formatUsd(value) {
        if (value === null || value === undefined) return "–";
        return "$" + Number(value).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function formatSignedPct(pct) {
        var sign = pct > 0 ? "+" : "";
        return sign + pct.toFixed(2) + "%";
    }

    function formatClock(ms) {
        var total = Math.max(0, Math.ceil(ms / 1000));
        var m = Math.floor(total / 60);
        var s = total % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    /* ---- fetching -------------------------------------------------------------------- */

    function fetchRound() {
        return window.CandleAuth.authFetch("/api/live/round?asset=" + state.asset)
            .then(function (res) {
                if (!res.ok) throw new Error("Không tải được vòng hiện tại.");
                return res.json();
            });
    }

    function fetchHistory() {
        return fetch("/api/live/history?asset=" + state.asset)
            .then(function (res) {
                if (!res.ok) throw new Error("Không tải được lịch sử.");
                return res.json();
            });
    }

    /* The two fetches are independent — round data and history data serve different parts of
       the screen — so one failing must not blank out the other. Promise.all used to reject the
       whole call the instant either side did, which meant a transient history-endpoint hiccup
       (its own, tighter rate limit) froze the price and countdown too, even though the round
       fetch had already succeeded. Each branch now reports its own failure and leaves whatever
       state it already had alone. */
    function loadAll() {
        el.status.textContent = "Đang tải…";
        var roundOk = fetchRound().then(function (round) {
            state.round = round;
            return true;
        }).catch(function (e) {
            el.status.textContent = "Lỗi: " + e.message;
            return false;
        });
        var historyOk = fetchHistory().then(function (history) {
            state.history = history.rounds;
            return true;
        }).catch(function (e) {
            if (!el.status.textContent || el.status.textContent === "Đang tải…") {
                el.status.textContent = "Lỗi: " + e.message;
            }
            return false;
        });
        return Promise.all([roundOk, historyOk]).then(function (results) {
            if (results[0]) {
                if (results[1]) el.status.textContent = "";
                render();
            }
        });
    }

    /* ---- rendering -------------------------------------------------------------------- */

    function render() {
        var r = state.round;
        if (!r) return;

        el.symbol.textContent = SYMBOL_NAME[r.asset] || r.asset;
        el.roundNumber.textContent = "Vòng #" + r.roundNumber;
        el.roundState.textContent = r.locked ? "Đã khoá — chờ đóng nến" : "Đang mở";
        el.roundState.className = "live-round-state " + (r.locked ? "is-locked" : "is-open");

        var price = r.livePrice !== null && r.livePrice !== undefined ? r.livePrice : r.openPrice;
        window.CandleRolling.update(el.price, formatUsd(price));

        if (r.openPrice && price !== null && price !== undefined) {
            var pct = (Number(price) - Number(r.openPrice)) / Number(r.openPrice) * 100;
            window.CandleRolling.update(el.delta, formatSignedPct(pct));
            el.delta.className = "market-delta live-delta rolling " + (pct >= 0 ? "outcome-up" : "outcome-down");
        } else {
            el.delta.textContent = "";
        }
        el.openNote.textContent = r.openPrice ? "Giá mở vòng: " + formatUsd(r.openPrice) : "";

        renderPool(r.longCount, r.shortCount);
        renderButtons(r);
        renderSparkline();
        renderHistory();
        tick(); // paint the countdown immediately rather than waiting for the first interval tick
    }

    function renderPool(longCount, shortCount) {
        var total = longCount + shortCount;
        var pct = total === 0 ? 50 : Math.round((longCount / total) * 100);
        el.poolLong.style.width = pct + "%";
        el.poolLongLabel.textContent = "LONG " + pct + "%";
        el.poolShortLabel.textContent = (100 - pct) + "% SHORT";
    }

    function renderButtons(r) {
        var locked = r.locked;
        var chosen = r.myDirection;
        el.longBtn.disabled = locked || !!chosen;
        el.shortBtn.disabled = locked || !!chosen;
        el.longBtn.classList.toggle("chosen", chosen === "LONG");
        el.shortBtn.classList.toggle("chosen", chosen === "SHORT");
        if (chosen) {
            el.status.textContent = "Bạn đã chọn " + chosen + " cho vòng này.";
        } else if (locked) {
            el.status.textContent = "Vòng đã khoá. Chờ vòng tiếp theo.";
        } else if (!el.status.textContent) {
            el.status.textContent = "";
        }
    }

    /* Oldest to newest, left to right — history.rounds arrives newest first. */
    function renderSparkline() {
        var rounds = state.history.slice().reverse();
        var ns = "http://www.w3.org/2000/svg";
        el.sparkline.innerHTML = "";
        if (rounds.length < 2) return;

        var closes = rounds.map(function (r) { return Number(r.closePrice); });
        var min = Math.min.apply(null, closes);
        var max = Math.max.apply(null, closes);
        var span = max - min || 1;
        var w = 300, h = 60, pad = 4;

        var points = closes.map(function (c, i) {
            var x = (i / (closes.length - 1)) * w;
            var y = h - pad - ((c - min) / span) * (h - pad * 2);
            return x.toFixed(1) + "," + y.toFixed(1);
        }).join(" ");

        var line = document.createElementNS(ns, "polyline");
        line.setAttribute("class", "live-sparkline-line");
        line.setAttribute("points", points);
        el.sparkline.appendChild(line);
    }

    function renderHistory() {
        el.historyStrip.innerHTML = "";
        state.history.forEach(function (r) {
            var item = document.createElement("button");
            item.type = "button";
            item.className = "live-history-item " + (r.result === "LONG" ? "lh-long" : "lh-short");
            var num = document.createElement("span");
            num.className = "lh-round";
            num.textContent = "Vòng #" + r.roundNumber;
            var res = document.createElement("span");
            res.className = "lh-result";
            res.textContent = (r.result === "LONG" ? "LONG" : "SHORT") + " thắng";
            item.appendChild(num);
            item.appendChild(res);
            item.addEventListener("click", function () { openRoundDetail(r.roundNumber); });
            el.historyStrip.appendChild(item);
        });
    }

    /* ---- countdown -------------------------------------------------------------------- */

    function stopTicking() {
        clearInterval(tickTimer);
        tickTimer = null;
    }

    function startTicking() {
        stopTicking();
        tick();
        tickTimer = setInterval(tick, 250);
    }

    function tick() {
        var r = state.round;
        if (!r) return;
        var now = Date.now();
        var lockAt = new Date(r.lockAt).getTime();
        var closeAt = new Date(r.closeAt).getTime();
        var openAt = new Date(r.openTime).getTime();

        var target = now < lockAt ? lockAt : closeAt;
        var windowStart = now < lockAt ? openAt : lockAt;
        var total = Math.max(1, target - windowStart);
        var left = Math.max(0, target - now);

        el.timerFill.style.width = ((left / total) * 100).toFixed(1) + "%";
        el.timerValue.textContent = (now < lockAt ? "Khoá sau " : "Đóng sau ") + formatClock(left);

        // Crossed lock or close since the last poll: refresh from the server rather than guess.
        if (left === 0) loadAll();
    }

    /* ---- actions ------------------------------------------------------------------------ */

    function place(direction) {
        el.longBtn.disabled = true;
        el.shortBtn.disabled = true;
        window.CandleAuth.authFetch("/api/live/predict", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ asset: state.asset, direction: direction }),
        })
            .then(function (res) {
                return res.json().then(function (body) {
                    if (!res.ok) throw new Error(body.message || "Không đặt được dự đoán.");
                    return body;
                });
            })
            .then(function (round) {
                state.round = round;
                render();
            })
            .catch(function (e) {
                el.status.textContent = e.message;
                render(); // re-enables the buttons if they are still eligible
            });
    }

    /* ---- lifecycle ------------------------------------------------------------------- */

    function stopPolling() {
        clearInterval(pollTimer);
        pollTimer = null;
        stopTicking();
    }

    function startPolling() {
        stopPolling();
        pollTimer = setInterval(function () {
            fetchRound().then(function (round) {
                state.round = round;
                render();
            }).catch(function () { /* transient — the next tick tries again */ });
        }, POLL_MS);
        startTicking();
    }

    /* ---- round-detail popup ----------------------------------------------------------- */

    var modalOpenerButton = null;

    function openRoundDetail(roundNumber) {
        modalOpenerButton = document.activeElement;
        el.modal.classList.remove("hidden");
        el.modalClose.focus();
        el.modalEyebrow.textContent = "4H CANDLE · VÒNG #" + roundNumber;
        el.modalTitle.textContent = (SYMBOL_NAME[state.asset] || state.asset) + " — vòng đã kết thúc";
        el.modalBadgeState.textContent = "";
        el.modalBadgeResult.textContent = "";
        el.modalBadgeResult.className = "live-round-modal-badge-result";
        el.modalOpen.textContent = "–";
        el.modalClosePrice.textContent = "–";
        el.modalPoolLongLabel.textContent = "";
        el.modalPoolShortLabel.textContent = "";
        el.modalPoolLong.style.width = "50%";
        el.modalStatus.textContent = "Đang tải…";
        while (el.modalChart.firstChild) el.modalChart.removeChild(el.modalChart.firstChild);

        fetch("/api/live/history/" + roundNumber + "?asset=" + state.asset)
            .then(function (res) {
                return res.json().then(function (body) {
                    if (!res.ok) throw new Error(body.message || "Không tải được vòng này.");
                    return body;
                });
            })
            .then(function (detail) { renderRoundDetail(detail); })
            .catch(function (e) { el.modalStatus.textContent = "Lỗi: " + e.message; });
    }

    function renderRoundDetail(detail) {
        el.modalStatus.textContent = "";
        el.modalBadgeState.textContent = "VÒNG #" + detail.roundNumber + " ĐÃ KẾT THÚC";
        el.modalBadgeResult.textContent = (detail.result === "LONG" ? "LONG" : "SHORT") + " thắng vòng này";
        el.modalBadgeResult.classList.add(detail.result === "LONG" ? "outcome-up" : "outcome-down");
        el.modalOpen.textContent = formatUsd(detail.openPrice);
        el.modalClosePrice.textContent = formatUsd(detail.closePrice);

        var total = detail.longCount + detail.shortCount;
        var pct = total === 0 ? 50 : Math.round((detail.longCount / total) * 100);
        el.modalPoolLong.style.width = pct + "%";
        el.modalPoolLongLabel.textContent = "LONG " + pct + "%";
        el.modalPoolShortLabel.textContent = (100 - pct) + "% SHORT";

        // The line marks where the round finished, not where it started — colored by who won,
        // the same read the badge below gives in words. No referenceLabel: the module's own
        // compact "K" shorthand is what fits the tag inside a 480px chart without it running
        // past the frame the way the price box's full "$78,898.06" would.
        window.CandleChart.draw(el.modalChart, detail.context, {
            referencePrice: detail.closePrice,
            referenceColor: detail.result === "LONG" ? "up" : "down",
        });
    }

    function closeRoundDetail() {
        el.modal.classList.add("hidden");
        if (modalOpenerButton) { modalOpenerButton.focus(); modalOpenerButton = null; }
    }

    /* Same reasoning as the practice tab: don't spend a poll on a tab nobody is looking at. */
    function isAway() {
        return document.visibilityState === "hidden"
            || document.getElementById("view-live").classList.contains("hidden");
    }

    document.addEventListener("visibilitychange", function () {
        if (isAway()) stopPolling(); else if (!pollTimer) startPolling();
    });
    document.addEventListener("candles:view", function (event) {
        if (event.detail && event.detail.view === "live") { if (!pollTimer) startPolling(); }
        else stopPolling();
    });

    var inited = false;

    window.__initLiveView = function () {
        if (inited) return;
        inited = true;

        window.CandlePill.attach(el.assetPill, ".pill-option");
        Array.prototype.slice.call(el.assetPill.querySelectorAll(".pill-option")).forEach(function (btn) {
            btn.addEventListener("click", function () {
                if (btn.classList.contains("active")) return;
                Array.prototype.slice.call(el.assetPill.querySelectorAll(".pill-option"))
                    .forEach(function (b) { b.classList.toggle("active", b === btn); });
                state.asset = btn.dataset.asset;
                loadAll().then(startPolling);
            });
        });

        el.longBtn.addEventListener("click", function () { place("LONG"); });
        el.shortBtn.addEventListener("click", function () { place("SHORT"); });

        el.modalClose.addEventListener("click", closeRoundDetail);
        el.modalBackdrop.addEventListener("click", closeRoundDetail);
        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape" && !el.modal.classList.contains("hidden")) closeRoundDetail();
        });

        loadAll().then(startPolling);
    };
})();
