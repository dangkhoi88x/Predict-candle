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
        participantsList: document.getElementById("live-participants-list"),
        participantsEmpty: document.getElementById("live-participants-empty"),
        participantsTitle: document.getElementById("live-participants-title"),
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
        renderParticipants(r.participants || []);
        renderSparkline();
        renderHistory();
        tick(); // paint the countdown immediately rather than waiting for the first interval tick
    }

    function formatTime(iso) {
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    /* The roster under the pool bar — who called this round and which way, the same
       social-proof read a live crowd gives at a glance. Rebuilt from scratch on every poll,
       the same as the history strip and sparkline: the list is short (capped server-side at
       50) and changes at most once every few seconds, so the DOM churn this costs is not
       worth guarding against for the gain of a fancier diff. */
    /* A fixed emoji + a background color chosen by hashing the wallet-short string, not the
       display name — the wallet is the one field that never changes for an account even if
       an admin renames it, so the same person keeps the same avatar. Six colors and twelve
       emoji give 72 combinations, plenty to make a short roster look like distinct people
       rather than a repeating pattern. */
    var AVATAR_EMOJI = ["🦊", "🐻", "🐼", "🦁", "🐯", "🐨", "🐰", "🐸", "🐙", "🦝", "🐺", "🐵"];

    function hashCode(text) {
        var h = 0;
        for (var i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0;
        return Math.abs(h);
    }

    function renderParticipants(participants) {
        el.participantsList.innerHTML = "";
        el.participantsEmpty.classList.toggle("hidden", participants.length > 0);
        el.participantsTitle.textContent = participants.length
            ? "Người chơi vòng này (" + participants.length + ")"
            : "Người chơi vòng này";

        participants.forEach(function (p) {
            var row = document.createElement("div");
            row.className = "live-participant-row";

            var seed = hashCode(p.walletShort || p.displayName || "?");
            var avatar = document.createElement("span");
            avatar.className = "live-participant-avatar avatar-bg-" + (seed % 6);
            avatar.textContent = AVATAR_EMOJI[seed % AVATAR_EMOJI.length];
            avatar.setAttribute("aria-hidden", "true");

            var info = document.createElement("div");
            info.className = "live-participant-info";
            var name = document.createElement("span");
            name.className = "live-participant-name";
            name.textContent = p.displayName;
            info.appendChild(name);
            // Only when it says something the name doesn't already: an un-renamed account's
            // display name already *is* this shorthand, so a second identical line would be
            // pure repetition — this is for the "Raccon" case, not the common one.
            if (p.walletShort && p.walletShort !== p.displayName) {
                var wallet = document.createElement("span");
                wallet.className = "live-participant-wallet";
                wallet.textContent = p.walletShort;
                info.appendChild(wallet);
            }

            var meta = document.createElement("div");
            meta.className = "live-participant-meta";
            var direction = document.createElement("span");
            direction.className = "live-participant-direction " + (p.direction === "LONG" ? "dir-long" : "dir-short");
            direction.textContent = p.direction === "LONG" ? "↗ LONG" : "↘ SHORT";
            var time = document.createElement("span");
            time.className = "live-participant-time";
            time.textContent = formatTime(p.createdAt);
            meta.appendChild(direction);
            meta.appendChild(time);

            row.appendChild(avatar);
            row.appendChild(info);
            row.appendChild(meta);
            el.participantsList.appendChild(row);
        });
    }

    function renderPool(longCount, shortCount) {
        var total = longCount + shortCount;
        var pct = total === 0 ? 50 : Math.round((longCount / total) * 100);
        el.poolLong.style.width = pct + "%";
        el.poolLongLabel.textContent = "LONG " + pct + "%";
        el.poolShortLabel.textContent = (100 - pct) + "% SHORT";
    }

    /* Whether the status line currently holds a message renderButtons itself put there, as
       opposed to a fetch error loadAll()/place() reported. Needed because "nothing left to
       say" has to become blank, but only by overwriting text this function owns — clearing an
       error message just because the button state changed would bury the thing the player
       actually needs to see. Caught by testing this against the running app, not by any unit
       test: connecting a wallet re-enabled the buttons correctly but left "Kết nối ví để dự
       đoán." on screen, because the old code only ever cleared the line when it was already
       empty — a no-op dressed up as a branch. */
    function statusOwnedByButtons(text) {
        return text === "" || text === "Vòng đã khoá. Chờ vòng tiếp theo."
            || text === "Kết nối ví để dự đoán." || text.indexOf("Bạn đã chọn ") === 0;
    }

    function renderButtons(r) {
        var locked = r.locked;
        var chosen = r.myDirection;
        // Disabled up front rather than left clickable and refused by the server after the
        // fact: a click that was always going to come back 401 is a worse first answer than
        // a button that already says what is missing.
        var signedIn = !!window.CandleAuth.getAccessToken();
        var disabled = locked || !!chosen || !signedIn;
        el.longBtn.disabled = disabled;
        el.shortBtn.disabled = disabled;
        el.longBtn.classList.toggle("chosen", chosen === "LONG");
        el.shortBtn.classList.toggle("chosen", chosen === "SHORT");

        if (chosen) {
            el.status.textContent = "Bạn đã chọn " + chosen + " cho vòng này.";
        } else if (locked) {
            el.status.textContent = "Vòng đã khoá. Chờ vòng tiếp theo.";
        } else if (!signedIn) {
            el.status.textContent = "Kết nối ví để dự đoán.";
        } else if (statusOwnedByButtons(el.status.textContent)) {
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
            var item = document.createElement("div");
            item.className = "live-history-item " + (r.result === "LONG" ? "lh-long" : "lh-short");
            var num = document.createElement("span");
            num.className = "lh-round";
            num.textContent = "Vòng #" + r.roundNumber;
            var res = document.createElement("span");
            res.className = "lh-result";
            res.textContent = (r.result === "LONG" ? "LONG" : "SHORT") + " thắng";
            item.appendChild(num);
            item.appendChild(res);
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

        /* Connecting or disconnecting a wallet changes whether the buttons should be
           clickable at all — without this, that only took effect on the next poll, up to
           POLL_MS late, so a player who just connected saw disabled buttons for a moment
           that had nothing to do with the round. */
        document.addEventListener("candles:session", function () { render(); });

        loadAll().then(startPolling);
    };
})();
