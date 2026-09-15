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
        hint: document.getElementById("daily-hint"),
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
        lesson: document.getElementById("daily-lesson"),
        lessonText: document.getElementById("daily-lesson-text"),
        lessonAction: document.getElementById("daily-lesson-action"),
        replay: document.getElementById("daily-replay"),
        replayLabel: document.getElementById("daily-replay-label"),
        back: document.getElementById("daily-back"),
        archive: document.getElementById("daily-archive"),
        shareText: document.getElementById("daily-share-text"),
        board: document.querySelector("#view-daily .daily-board"),
        finishers: document.getElementById("daily-finishers"),
        title: document.getElementById("daily-title"),
        intro: document.getElementById("daily-intro"),
        start: document.getElementById("daily-start"),
        startTitle: document.getElementById("daily-start-title"),
        startNote: document.getElementById("daily-start-note"),
        startButton: document.getElementById("daily-start-button"),
    };

    var state = null;
    var candles = [];
    var results = [];
    var hints = null;
    var token = null;
    /* Which day is on the board: null is today, a "YYYY-MM-DD" string is a replay. Every
       request and every label reads this, so the board can only ever be showing one day. */
    var archiveDay = null;
    /* A friend's challenge on the board instead of a day: "/?thach=<id>" puts it here. Same board,
       same clock, same one attempt; the server keeps its guesses apart from everything that scores
       (V18). Null whenever a day is on the board, so the two can never both be. */
    var challengeId = null;
    /* Every load() bumps this and a response for an older one is dropped. Opening a challenge
       reveals the tab, which loads today, and then loads the challenge — whichever answered last
       used to win. */
    var loadSeq = 0;
    var lastArchive = [];
    var timerId = null;
    var deadline = 0;
    var timeoutsInARow = 0;

    /* ---- the start button ------------------------------------------------------------------

       Opening this tab used to deal the day's chart and start its clock at once, so a player who
       only came to look spent their one attempt of the day watching it expire. The clock itself
       stays unpausable — the server measures it from the token, and a pausable clock on a chart
       you get one go at is an invitation to park it — so the answer is not to pause it but not to
       start it: the chart and its clock appear together, when the player presses start.

       The board reads the round on reveal to know which state it is in, but draws nothing until
       then, and pressing start reads it again for a fresh token.

       A chain of timeouts stops the same way the practice game's does (see app.js): a timeout
       while this tab is not on screen, or a second in a row, records that call and shows the
       button again rather than dealing the next guess to nobody. Signed in, "Tiếp tục" resumes at
       the next guess, because the recorded guesses are the attempt. Signed out nothing was
       recorded, so it starts the day again — which anonymous play could always do by reloading. */
    var IDLE_TIMEOUT_STREAK = 2;

    function away() {
        return document.visibilityState === "hidden" || document.getElementById("view-daily").classList.contains("hidden");
    }

    function showGate(guessesMade, reason) {
        stopTimer();
        token = null;
        setPlaying(false);
        el.board.classList.add("is-gated");
        el.start.classList.remove("hidden");
        el.hint.classList.add("hidden");
        el.done.classList.add("hidden");
        hideLesson();
        while (el.chart.firstChild) el.chart.removeChild(el.chart.firstChild);

        var total = state ? state.totalGuesses : 5;
        var seconds = state ? state.guessSeconds : 20;
        if (reason === "idle" || reason === "away") {
            el.startTitle.textContent = "Tạm dừng";
            el.startNote.textContent = (reason === "idle"
                ? "Bạn đã để hết giờ " + IDLE_TIMEOUT_STREAK + " nến liền"
                : "Hết giờ trong lúc bạn rời tab này") + ", nên thử thách tạm dừng ở đây.";
            el.startButton.textContent = "Tiếp tục";
        } else if (guessesMade > 0) {
            el.startTitle.textContent = "Tiếp tục thử thách";
            el.startNote.textContent = "Bạn đã đoán " + guessesMade + "/" + total + " nến. Đồng hồ "
                + seconds + " giây chạy lại khi bạn bấm.";
            el.startButton.textContent = "Tiếp tục từ nến " + (guessesMade + 1);
        } else if (challengeId && state) {
            el.startTitle.textContent = state.creatorName + " đoán đúng " + state.creatorCorrect + "/" + total
                + " nến trên chart này";
            el.startNote.textContent = "Bạn làm được không? " + total + " nến, " + seconds + " giây mỗi nến."
                + (window.CandleAuth.getUser()
                    ? " Một lượt, kết quả được ghi lên bảng của thách đấu."
                    : " Kết nối ví hoặc email để kết quả được ghi lên bảng của thách đấu.");
            el.startButton.textContent = "Nhận thách đấu";
        } else {
            el.startTitle.textContent = archiveDay ? "Chơi lại ngày này?" : "Sẵn sàng?";
            el.startNote.textContent = total + " nến, " + seconds + " giây mỗi nến"
                + (archiveDay ? "." : ", mỗi ngày một lượt.") + " Biểu đồ và đồng hồ chỉ bắt đầu khi bạn bấm.";
            el.startButton.textContent = archiveDay ? "Bắt đầu chơi lại" : "Bắt đầu thử thách";
        }
        el.status.textContent = "";
    }

    function hideGate() {
        el.board.classList.remove("is-gated");
        el.start.classList.add("hidden");
    }

    function toChartCandle(c) {
        // CandleChart wants numbers; the API sends decimals as JSON numbers already, but the
        // reference line and scaling do arithmetic on them, so coerce rather than trust.
        return { time: null, open: +c.open, high: +c.high, low: +c.low, close: +c.close };
    }

    function drawChart() {
        window.CandleChart.draw(el.chart, candles.map(toChartCandle), {
            volumes: hints && hints.volumes,
            lines: hints && hints.movingAverage ? [{ values: hints.movingAverage }] : [],
        });
    }

    /* Names what just appeared, newest rung first. A hint that arrives unannounced reads as a
       rendering glitch; saying it out loud makes it what it is — the game giving ground because
       the player is struggling. */
    function renderHint() {
        var lines = [];
        if (hints && hints.patternId) {
            var name = window.CandlePatterns && window.CandlePatterns.nameOf
                ? window.CandlePatterns.nameOf(hints.patternId) : hints.patternId;
            lines.push("mẫu nến gần đây: " + name);
        }
        if (hints && hints.movingAverage) lines.push("đường trung bình 5 nến");
        if (hints && hints.volumes) lines.push("khối lượng");

        el.hint.classList.toggle("hidden", lines.length === 0);
        if (lines.length) el.hint.textContent = "Gợi ý đã mở — " + lines.join(" · ");
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
    function roundUrl() {
        if (challengeId) return "/api/challenges/" + encodeURIComponent(challengeId);
        return archiveDay ? "/api/daily/archive/" + archiveDay : "/api/daily/round";
    }

    function guessUrl() {
        if (challengeId) return "/api/challenges/" + encodeURIComponent(challengeId) + "/guess";
        return archiveDay ? "/api/daily/archive/" + archiveDay + "/guess" : "/api/daily/guess";
    }

    /** Only today's challenge feeds the daily funnel; a replay or a friend's challenge is its own step. */
    function isToday() {
        return !archiveDay && !challengeId;
    }

    /** The share text without its link; {@link sharePath} is the link, kept apart for Telegram's sheet. */
    function shareText() {
        var correct = results.filter(Boolean).length;
        if (challengeId) {
            return "Mình đoán đúng " + correct + "/" + results.length + " nến trên chart "
                + state.creatorName + " thách (họ đúng " + state.creatorCorrect + "/" + state.totalGuesses
                + "). Bạn thử không?";
        }
        var dots = results.map(function (ok) { return ok ? "🟩" : "🟥"; }).join("");
        return "Candle Guess #" + state.roundNumber + " — " + correct + "/" + results.length + "\n" + dots;
    }

    function shareLabel() {
        return window.CandleTelegram && window.CandleTelegram.inside() ? "Gửi kết quả qua Telegram" : "Sao chép kết quả";
    }

    function sharePath() {
        return challengeId ? "/?thach=" + challengeId : "/";
    }

    async function copyShare() {
        if (window.CandleAnalytics) window.CandleAnalytics.track(challengeId ? "challenge-share" : "daily-share");
        if (window.CandleTelegram && window.CandleTelegram.share(shareText(), sharePath())) return;
        var text = shareText() + "\n" + window.location.origin + (challengeId ? sharePath() : "");
        try {
            await navigator.clipboard.writeText(text);
            el.share.textContent = "Đã sao chép";
            setTimeout(function () { el.share.textContent = shareLabel(); }, 2000);
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

    /* ---- the lesson ---------------------------------------------------------------------------

       One thing to take away from the round just finished. In order:

       1. A candlestick pattern that completed on the last candle before a guess the player missed —
          the most specific lesson there is: this was on the chart, and it was read wrong.
       2. The player's biggest habit from /api/stats/me/insights, when signed in and there is one.
       3. A pattern the chart held anywhere in the played stretch, to go and look at.
       4. Otherwise, why signing in and playing more will make this card say something.

       Patterns come from the finishing guess's `context`, which only that response carries — a
       completed round read back later has none. So the pattern is picked at the moment of finishing
       and held in `chartLesson`; a player reopening a finished day still gets 2 or 4. */
    var chartLesson = null;
    var lessonToken = 0;

    /** @return {missed: boolean, patternId, guessNumber} or null */
    function lessonFromContext(context, outcomes) {
        if (!context || !context.patterns || !context.patterns.length) return null;
        var seen = null;
        for (var k = 1; k <= outcomes.length; k++) {
            // Last candle the player could see before guess k, in the context's own coordinates.
            var lastVisible = context.guessFrom + k - 2;
            for (var i = 0; i < context.patterns.length; i++) {
                var mark = context.patterns[i];
                if (mark.startIndex + mark.length - 1 !== lastVisible) continue;
                if (!outcomes[k - 1]) return { missed: true, patternId: mark.patternId, guessNumber: k };
                if (!seen) seen = { missed: false, patternId: mark.patternId, guessNumber: k };
            }
        }
        if (seen) return seen;
        // A pattern elsewhere in what they looked at still names something worth reading; the
        // marks come sorted by where they end, so the last is the nearest the guessing.
        var latest = context.patterns[context.patterns.length - 1];
        return { missed: false, patternId: latest.patternId, guessNumber: null };
    }

    function setLessonAction(label, onClick) {
        el.lessonAction.classList.toggle("hidden", !label);
        el.lessonAction.textContent = label || "";
        el.lessonAction.onclick = label ? function () {
            if (window.CandleAnalytics) window.CandleAnalytics.track("daily-lesson-click");
            onClick();
        } : null;
    }

    function showPatternLesson(lesson) {
        var name = window.CandleInsights.patternName(lesson.patternId);
        if (lesson.missed) {
            el.lessonText.textContent = "Ngay trước nến thứ " + lesson.guessNumber + ", chart có mẫu " + name
                + " và bạn đã đoán sai ở nến đó. Xem lại mẫu này báo hiệu điều gì trước khi gặp nó lần sau.";
        } else if (lesson.guessNumber) {
            el.lessonText.textContent = "Ngay trước nến thứ " + lesson.guessNumber + ", chart có mẫu " + name
                + ". Bạn đã đọc đúng lần này; xem lại để nhận ra nó nhanh hơn.";
        } else {
            el.lessonText.textContent = (challengeId ? "Chart này" : "Chart hôm nay") + " có mẫu " + name
                + ". Xem lại cách nhận diện nó.";
        }
        setLessonAction("Xem mẫu " + name, function () { window.CandlePatterns.reveal(lesson.patternId); });
    }

    async function renderLesson() {
        var token = ++lessonToken;
        el.lesson.classList.remove("hidden");

        if (chartLesson && chartLesson.missed) {
            showPatternLesson(chartLesson);
            return;
        }

        el.lessonText.textContent = "Đang chọn bài học…";
        setLessonAction(null);
        var insights = await window.CandleInsights.load();
        if (token !== lessonToken) return; // a newer round replaced this one while it loaded

        var finding = insights && insights.findings.length ? insights.findings[0] : null;
        var text = finding ? window.CandleInsights.sentence(finding, insights) : null;
        if (text) {
            el.lessonText.textContent = text;
            setLessonAction("Xem thói quen của bạn", function () { window.CandleNav.go("profile"); });
            return;
        }
        if (chartLesson) {
            if (window.CandlePatterns) await window.CandlePatterns.whenLoaded();
            if (token !== lessonToken) return;
            showPatternLesson(chartLesson);
            return;
        }
        if (!window.CandleAuth.getUser()) {
            // 30 is the server's PlayerInsights.MIN_SAMPLE; signed out there is no response to read it from.
            el.lessonText.textContent = "Kết nối ví hoặc email để lưu kết quả. Sau 30 lượt đoán, "
                + "game sẽ chỉ ra thói quen khiến bạn hay đoán sai.";
        } else if (insights && !insights.enough) {
            var answered = insights.calls.longCalls + insights.calls.shortCalls;
            el.lessonText.textContent = "Còn " + (insights.minSample - answered)
                + " lượt đoán nữa là game phân tích được thói quen của bạn.";
        } else {
            el.lessonText.textContent = "Chưa thấy thói quen lệch rõ rệt nào. Giữ nhịp đó cho ngày mai.";
        }
        setLessonAction(null);
    }

    function hideLesson() {
        lessonToken++;
        chartLesson = null;
        el.lesson.classList.add("hidden");
    }

    function showDone() {
        stopTimer();
        setPlaying(false);
        el.done.classList.remove("hidden");

        var correct = results.filter(Boolean).length;
        renderFinishers();

        if (challengeId) {
            el.share.textContent = "Thách tiếp người khác";
            el.share.classList.toggle("hidden", !!state.mine);
            if (state.mine) {
                el.doneLine.textContent = "Bạn tạo thử thách này với " + state.creatorCorrect + "/" + state.totalGuesses + " nến.";
                el.next.textContent = "Bạn đã biết đáp án của chart này nên không chơi lại được. Bảng bên dưới là kết quả của người được thách.";
                el.status.textContent = "";
                return;
            }
            var theirs = state.creatorCorrect;
            el.doneLine.textContent = "Bạn đúng " + correct + "/" + results.length + " — " + state.creatorName
                + " đúng " + theirs + "/" + state.totalGuesses + ".";
            el.next.textContent = correct > theirs ? "Bạn thắng thách đấu này."
                : correct === theirs ? "Hoà." : "Lần này " + state.creatorName + " đọc chart tốt hơn.";
            el.status.textContent = "Xong thách đấu.";
            renderLesson();
            return;
        }

        el.share.textContent = shareLabel();
        el.share.classList.remove("hidden");
        el.doneLine.textContent = "Bạn đoán đúng " + correct + "/" + results.length + " nến.";
        el.status.textContent = "Xong thử thách hôm nay.";

        var next = new Date(state.nextRoundAt);
        el.next.textContent = archiveDay ? "" : "Thử thách tiếp theo mở lúc "
            + next.toLocaleString("vi-VN", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" })
            + " (giờ của bạn).";

        renderLesson();
    }

    async function submit(direction) {
        if (!token) return;
        stopTimer();
        setPlaying(false);

        var body = direction
            ? { roundToken: token, direction: direction }
            : { roundToken: token };

        try {
            var res = await window.CandleAuth.authFetch(guessUrl(), {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            // Today's challenge only: an archive replay is a different step of a different funnel.
            if (direction && isToday() && window.CandleAnalytics) window.CandleAnalytics.trackOnce("daily-first-guess");

            candles.push(payload.actualCandle);
            results.push(payload.correct);
            timeoutsInARow = direction ? 0 : timeoutsInARow + 1;
            token = payload.nextRoundToken;
            hints = payload.hints;
            drawChart();
            renderDots();
            renderHint();

            if (payload.sessionComplete) {
                if (window.CandleAnalytics) window.CandleAnalytics.track(challengeId ? "challenge-complete" : isToday() ? "daily-complete" : "archive-complete");
                // Only this response carries the chart's patterns; take the lesson from it now.
                chartLesson = lessonFromContext(payload.context, results);
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
                // The day just moved from unplayed to finished, and the list says so.
                if (archiveDay) await loadArchive();
                return;
            }
            if (!direction && (away() || timeoutsInARow >= IDLE_TIMEOUT_STREAK)) {
                showGate(results.length, away() ? "away" : "idle");
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

    /* The list is a separate read from the board on purpose: it changes only when a replay
       finishes, and refetching it on every guess would be a request per candle. */
    async function loadArchive() {
        try {
            var res = await window.CandleAuth.authFetch("/api/daily/archive?days=14");
            if (!res.ok) throw new Error("Máy chủ trả về " + res.status);
            lastArchive = await res.json();
            renderArchive(lastArchive);
        } catch (e) {
            el.archive.innerHTML =
                '<p class="profile-empty">Không tải được danh sách ngày trước.</p>';
        }
    }

    function renderArchive(rows) {
        el.archive.innerHTML = "";
        if (!rows.length) {
            el.archive.innerHTML = '<p class="profile-empty">Chưa có ngày nào để chơi lại.</p>';
            return;
        }
        rows.forEach(function (row) {
            var button = document.createElement("button");
            button.type = "button";
            button.className = "daily-archive-day"
                + (row.completed ? " is-done" : "")
                + (row.day === archiveDay ? " is-open" : "");

            var number = document.createElement("span");
            number.className = "daily-archive-number";
            number.textContent = "#" + row.roundNumber;

            var date = document.createElement("span");
            date.className = "daily-archive-date";
            var d = new Date(row.day + "T00:00:00Z");
            date.textContent = String(d.getUTCDate()).padStart(2, "0") + "/"
                + String(d.getUTCMonth() + 1).padStart(2, "0");

            var state = document.createElement("span");
            state.className = "daily-archive-state";
            // Three states, and they have to stay distinct: a finished day shows its score, a
            // half-played one says where it stopped, an untouched one invites.
            state.textContent = row.completed
                ? row.correct + "/" + row.totalGuesses
                : row.guessesMade > 0
                    ? "dở " + row.guessesMade + "/" + row.totalGuesses
                    : "chưa chơi";

            button.appendChild(number);
            button.appendChild(date);
            button.appendChild(state);
            button.addEventListener("click", function () { openDay(row.day); });
            el.archive.appendChild(button);
        });
    }

    async function openDay(day) {
        hideLesson();
        archiveDay = day;
        await load();
        renderArchive(lastArchive);
    }

    function renderReplayBanner() {
        // The heading says which kind of chart is on the board; "the same chart for everyone,
        // once a day" is not true of a link a friend sent.
        el.title.textContent = challengeId ? "Thách Đấu" : "Thử Thách Hôm Nay";
        el.intro.textContent = challengeId
            ? "Một người bạn đã chơi chart này và gửi nó cho bạn. Cùng biểu đồ, cùng luật."
            : "Cùng một biểu đồ cho tất cả mọi người, mỗi ngày một lần.";
        el.replay.classList.toggle("hidden", isToday());
        if (challengeId && state) {
            el.replayLabel.textContent = "Thách đấu từ " + state.creatorName;
        } else if (archiveDay && state) {
            el.replayLabel.textContent = "Đang chơi lại #" + state.roundNumber;
        }
    }

    /* Who has played the challenge through, best first. Only signed-in players — an anonymous
       attempt is never recorded — so an empty list says how to get onto it. */
    function renderFinishers() {
        el.finishers.innerHTML = "";
        el.finishers.classList.toggle("hidden", !challengeId);
        if (!challengeId || !state) return;
        var title = document.createElement("span");
        title.className = "side-eyebrow";
        title.textContent = "Bảng của thách đấu";
        el.finishers.appendChild(title);
        var list = document.createElement("ol");
        list.className = "daily-finishers-list";
        var creator = document.createElement("li");
        creator.className = "is-creator";
        creator.textContent = state.creatorName + " (người thách) — " + state.creatorCorrect + "/" + state.totalGuesses;
        list.appendChild(creator);
        (state.finishers || []).forEach(function (f) {
            var li = document.createElement("li");
            if (f.you) li.className = "is-you";
            li.textContent = (f.you ? "Bạn" : f.displayName) + " — " + f.correct + "/" + f.total;
            list.appendChild(li);
        });
        el.finishers.appendChild(list);
        if (!(state.finishers || []).length) {
            var empty = document.createElement("p");
            empty.className = "daily-finishers-empty";
            empty.textContent = window.CandleAuth.getUser()
                ? "Chưa ai khác chơi xong thách đấu này."
                : "Kết nối ví hoặc email rồi chơi để có tên trên bảng này.";
            el.finishers.appendChild(empty);
        }
    }

    /** @param play true when the player pressed start — only then is the chart drawn and timed. */
    async function load(play) {
        stopTimer();
        var seq = ++loadSeq;
        try {
            var res = await window.CandleAuth.authFetch(roundUrl());
            var payload = await res.json();
            if (seq !== loadSeq) return; // a later load() owns the board now
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));

            state = payload;
            candles = payload.candles.slice();
            results = payload.answers.map(function (a) { return a.correct; });
            hints = payload.hints;
            token = payload.roundToken;

            el.number.textContent = challengeId ? "Thách đấu" : "#" + payload.roundNumber;
            renderStreak(challengeId ? null : payload.streak);
            renderReplayBanner();

            if (payload.completed) {
                hideGate();
                // Drawing the answer candles is the payoff — the chart finishes in front of
                // them instead of staying frozen where they left off.
                // The chart resolves in front of them; the hints go, since there is nothing
                // left to guess and a chart still marked up reads as unfinished.
                candles = candles.concat(payload.resolvedCandles);
                hints = null;
                drawChart();
                renderDots();
                renderHint();
                showDone();
                return;
            }

            renderDots();
            if (!play) {
                showGate(payload.guessesMade);
                return;
            }

            hideGate();
            timeoutsInARow = 0;
            el.done.classList.add("hidden");
            hideLesson();
            drawChart();
            renderHint();
            el.status.textContent = payload.guessesMade > 0
                ? "Tiếp tục từ nến thứ " + (payload.guessesMade + 1) + "."
                : "Nến tiếp theo sẽ đi lên hay xuống?";
            setPlaying(true);
            startTimer();
        } catch (e) {
            if (seq !== loadSeq) return;
            el.status.textContent = (challengeId ? "Không tải được thách đấu: " : "Không tải được thử thách hôm nay: ") + e.message;
            setPlaying(false);
        }
    }

    el.startButton.addEventListener("click", function () {
        el.startButton.disabled = true;
        load(true).finally(function () { el.startButton.disabled = false; });
    });

    el.long.addEventListener("click", function () { submit("LONG"); });
    el.short.addEventListener("click", function () { submit("SHORT"); });
    el.share.addEventListener("click", copyShare);

    el.back.addEventListener("click", async function () {
        hideLesson();
        archiveDay = null;
        challengeId = null;
        renderFinishers();
        await load();
        renderArchive(lastArchive);
    });

    /* The clock is deliberately not paused when the tab is hidden or switched away from.
       Pausing it here would not pause the server, which measures from when it minted the token
       — a player who left and came back would find every answer refused, with no way forward.
       And a pausable clock is exactly the cheat that matters most on a chart you only get one
       go at: park the round, go and look the period up, come back and answer. Same rule the
       game tab already holds to for the same reason. */

    /* Opening the tab always lands on today, never on whichever day was last replayed: the
       daily is the thing this tab is for, and a player returning to find yesterday on screen
       would think they had already played. */
    window.__initDailyView = function () {
        // A lesson taken from a replayed day does not belong on today.
        if (!isToday()) hideLesson();
        archiveDay = null;
        challengeId = null;
        renderFinishers();
        loadArchive();
        return load();
    };

    /** Puts a friend's challenge on the board, behind its start button. */
    function openChallenge(id) {
        if (window.CandleNav) window.CandleNav.go("daily"); // loads today first; loadSeq drops that
        hideLesson();
        archiveDay = null;
        challengeId = id;
        state = null;
        if (window.CandleAnalytics) window.CandleAnalytics.track("challenge-open");
        return load();
    }

    /* "/?thach=<id>" is how a challenge link arrives. Read once every script has run — the same
       reason nav.js waits for ?view — and then taken out of the address bar, so a reload lands on
       the game rather than replaying the link. */
    document.addEventListener("DOMContentLoaded", function () {
        try {
            var params = new URLSearchParams(window.location.search);
            var id = params.get("thach");
            if (!id) return;
            params.delete("thach");
            var rest = params.toString();
            history.replaceState(null, "", window.location.pathname + (rest ? "?" + rest : "") + window.location.hash);
            if (/^[a-z0-9]{4,16}$/.test(id)) openChallenge(id);
        } catch (e) {
            // Without URLSearchParams the link opens the site normally.
        }
    });

    window.CandleDaily = { openChallenge: openChallenge };
})();
