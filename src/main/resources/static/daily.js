/* The daily challenge: one chart a day, the same one for everybody, one attempt.
 *
 * All state lives on the server — which chart today is, how far this player got, whether they
 * are finished. This file asks and draws. Nothing here decides anything the server would have
 * to agree with later, which is what lets a player reload, switch device or sign in halfway
 * through and still see the same round in the same place.
 *
 * The shot clock is the same one practice runs, and it is not decoration: the server refuses an
 * answered guess that arrives past the limit, so a countdown the player cannot see would cost
 * them the one attempt they get today. */
(function () {
    "use strict";

    var el = {
        number: document.getElementById("daily-number"),
        streak: document.getElementById("daily-streak"),
        streakCurrent: document.getElementById("daily-streak-current"),
        streakBest: document.getElementById("daily-streak-best"),
        chart: document.getElementById("daily-chart"),
        status: document.getElementById("daily-status"),
        dots: document.getElementById("daily-dots"),
        actions: document.getElementById("daily-actions"),
        long: document.getElementById("daily-long"),
        short: document.getElementById("daily-short"),
        timer: document.getElementById("daily-timer"),
        timerFill: document.getElementById("daily-timer-fill"),
        timerValue: document.getElementById("daily-timer-value"),
        done: document.getElementById("daily-done"),
        doneLine: document.getElementById("daily-done-line"),
        next: document.getElementById("daily-next"),
        share: document.getElementById("daily-share"),
        shareText: document.getElementById("daily-share-text"),
    };

    var state = null;
    var candles = [];
    var results = [];
    var token = null;
    var timerId = null;
    var deadline = 0;

    function toChartCandle(c) {
        // CandleChart wants numbers; the API sends decimals as JSON numbers already, but the
        // reference line and scaling do arithmetic on them, so coerce rather than trust.
        return { time: null, open: +c.open, high: +c.high, low: +c.low, close: +c.close };
    }

    function drawChart() {
        window.CandleChart.draw(el.chart, candles.map(toChartCandle));
    }

    function renderDots() {
        el.dots.innerHTML = "";
        var total = state ? state.totalGuesses : 0;
        for (var i = 0; i < total; i++) {
            var dot = document.createElement("span");
            var result = results[i];
            dot.className = "daily-dot"
                + (result === undefined ? "" : (result ? " is-correct" : " is-wrong"));
            dot.textContent = result === undefined ? "" : (result ? "✓" : "✕");
            el.dots.appendChild(dot);
        }
    }

    function renderStreak(streak) {
        // Signed out there is no streak to show — nothing is recorded, so nothing can be
        // claimed. Saying "0 ngày" would read as a streak that was lost.
        el.streak.classList.toggle("hidden", !streak);
        if (!streak) return;
        window.CandleRolling.update(el.streakCurrent, streak.current);
        el.streakBest.textContent = streak.best > streak.current
            ? "dài nhất " + streak.best + " ngày" : "";
    }

    function stopTimer() {
        if (timerId) clearInterval(timerId);
        timerId = null;
        el.timer.classList.add("hidden");
    }

    function startTimer() {
        stopTimer();
        var seconds = state.guessSeconds;
        deadline = Date.now() + seconds * 1000;
        el.timer.classList.remove("hidden");

        function tick() {
            var left = Math.max(0, deadline - Date.now());
            el.timerFill.style.width = (left / (seconds * 1000)) * 100 + "%";
            el.timerValue.textContent = Math.ceil(left / 1000) + "s";
            if (left <= 0) {
                stopTimer();
                submit(null);
            }
        }
        tick();
        timerId = setInterval(tick, 100);
    }

    function setPlaying(playing) {
        el.actions.classList.toggle("hidden", !playing);
        el.long.disabled = !playing;
        el.short.disabled = !playing;
    }

    /* The share text: the round number, the score, and the same dots that are on screen.
       Deliberately no asset name and no dates — that is the answer, and a result you cannot
       post without spoiling the puzzle is one nobody posts. */
    function shareText() {
        var correct = results.filter(Boolean).length;
        var dots = results.map(function (ok) { return ok ? "🟩" : "🟥"; }).join("");
        return "Candle Guess #" + state.roundNumber + " — " + correct + "/" + results.length
            + "\n" + dots + "\n" + window.location.origin;
    }

    async function copyShare() {
        var text = shareText();
        try {
            await navigator.clipboard.writeText(text);
            el.share.textContent = "Đã sao chép";
            setTimeout(function () { el.share.textContent = "Sao chép kết quả"; }, 2000);
        } catch (e) {
            /* Refused — an insecure origin, or a browser that wants a gesture it did not see.
               Putting the text on screen and selecting it is the difference between a share
               button that failed and one the player can still act on. */
            el.shareText.value = text;
            el.shareText.classList.remove("hidden");
            el.shareText.select();
            el.share.textContent = "Chép thủ công ở dưới";
        }
    }

    function showDone() {
        stopTimer();
        setPlaying(false);
        el.done.classList.remove("hidden");

        var correct = results.filter(Boolean).length;
        el.doneLine.textContent = "Bạn đoán đúng " + correct + "/" + results.length + " nến.";
        el.status.textContent = "Xong thử thách hôm nay.";

        var next = new Date(state.nextRoundAt);
        el.next.textContent = "Thử thách tiếp theo mở lúc "
            + next.toLocaleString("vi-VN", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" })
            + " (giờ của bạn).";
    }

    async function submit(direction) {
        if (!token) return;
        stopTimer();
        setPlaying(false);

        var body = direction
            ? { roundToken: token, direction: direction }
            : { roundToken: token };

        try {
            var res = await window.CandleAuth.authFetch("/api/daily/guess", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));

            candles.push(payload.actualCandle);
            results.push(payload.correct);
            token = payload.nextRoundToken;
            drawChart();
            renderDots();

            if (payload.sessionComplete) {
                /* Signed in, re-read: the streak only moves once the day is finished and the
                   server is the one that knows what it moved to.

                   Signed out, do not — nothing was recorded, so asking again returns today's
                   chart as though it had never been played, wiping the result off the screen
                   the moment it was earned. That is the one player who most needs to see the
                   share card, since it is the whole reason they might come back. */
                if (window.CandleAuth.getUser()) {
                    await load();
                } else {
                    showDone();
                }
                return;
            }
            el.status.textContent = "Nến tiếp theo: đoán hướng.";
            setPlaying(true);
            startTimer();
        } catch (e) {
            el.status.textContent = "Không gửi được lượt đoán: " + e.message;
            // The round is not lost — the server still holds the same chart, and reopening the
            // tab picks it up from the guess that did land.
            setPlaying(!!token);
        }
    }

    async function load() {
        stopTimer();
        try {
            var res = await window.CandleAuth.authFetch("/api/daily/round");
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));

            state = payload;
            candles = payload.candles.slice();
            results = payload.answers.map(function (a) { return a.correct; });
            token = payload.roundToken;

            el.number.textContent = "#" + payload.roundNumber;
            renderStreak(payload.streak);

            if (payload.completed) {
                // Drawing the answer candles is the payoff — the chart finishes in front of
                // them instead of staying frozen where they left off.
                candles = candles.concat(payload.resolvedCandles);
                drawChart();
                renderDots();
                    showDone();
                return;
            }

            el.done.classList.add("hidden");
            drawChart();
            renderDots();
            el.status.textContent = payload.guessesMade > 0
                ? "Tiếp tục từ nến thứ " + (payload.guessesMade + 1) + "."
                : "Nến tiếp theo sẽ đi lên hay xuống?";
            setPlaying(true);
            startTimer();
        } catch (e) {
            el.status.textContent = "Không tải được thử thách hôm nay: " + e.message;
            setPlaying(false);
        }
    }

    el.long.addEventListener("click", function () { submit("LONG"); });
    el.short.addEventListener("click", function () { submit("SHORT"); });
    el.share.addEventListener("click", copyShare);

    /* The clock is deliberately not paused when the tab is hidden or switched away from.
       Pausing it here would not pause the server, which measures from when it minted the token
       — a player who left and came back would find every answer refused, with no way forward.
       And a pausable clock is exactly the cheat that matters most on a chart you only get one
       go at: park the round, go and look the period up, come back and answer. Same rule the
       game tab already holds to for the same reason. */

    window.__initDailyView = load;
})();
