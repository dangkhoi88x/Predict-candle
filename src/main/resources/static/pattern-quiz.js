/* The day's pattern quiz: one real chart, four names, one answer.
 *
 * Lives on the daily tab but is its own module — its own endpoint, its own record, and its own
 * failure. A quiz that cannot load should leave the daily round beside it working.
 *
 * The server never sends which name is right until an answer has been given, so nothing here
 * has to be careful about not revealing it: there is nothing to reveal. */
(function () {
    "use strict";

    var el = {
        intro: document.getElementById("quiz-intro"),
        chart: document.getElementById("quiz-chart"),
        choices: document.getElementById("quiz-choices"),
        status: document.getElementById("quiz-status"),
    };

    var state = null;

    function nameOf(id) {
        return window.CandlePatterns && window.CandlePatterns.nameOf
            ? window.CandlePatterns.nameOf(id) : id;
    }

    function drawChart() {
        window.CandleChart.draw(el.chart, state.candles.map(function (c) {
            return { time: null, open: +c.open, high: +c.high, low: +c.low, close: +c.close };
        }), { highlight: { from: state.patternStartIndex, length: state.patternLength } });
    }

    function renderChoices() {
        el.choices.innerHTML = "";
        var signedIn = !!window.CandleAuth.getUser();

        state.choices.forEach(function (id) {
            var button = document.createElement("button");
            button.type = "button";
            button.className = "quiz-choice";
            button.textContent = nameOf(id);

            if (state.answered) {
                // Both sides of the result: what they picked, and what was right. Showing only
                // the verdict leaves a wrong answer with nothing to learn from.
                if (id === state.correctPatternId) button.classList.add("is-correct");
                if (id === state.guessedPatternId && !state.correct) button.classList.add("is-wrong");
                button.disabled = true;
            } else {
                button.disabled = !signedIn;
                button.addEventListener("click", function () { answer(id); });
            }
            el.choices.appendChild(button);
        });
    }

    function renderStatus() {
        if (state.answered) {
            el.status.textContent = state.correct
                ? "Chính xác — đây là " + nameOf(state.correctPatternId) + "."
                : "Chưa đúng. Đây là " + nameOf(state.correctPatternId) + ".";
            return;
        }
        el.status.textContent = window.CandleAuth.getUser()
            ? "Chọn một đáp án. Mỗi ngày một lượt."
            : "Kết nối ví để trả lời — mỗi ngày một lượt.";
    }

    function render() {
        el.intro.textContent = "Mẫu nến nào đang được đánh dấu? (#" + state.roundNumber + ")";
        drawChart();
        renderChoices();
        renderStatus();
    }

    async function answer(patternId) {
        try {
            var res = await window.CandleAuth.authFetch("/api/pattern-quiz/answer", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ patternId: patternId }),
            });
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            state = payload;
            render();
        } catch (e) {
            el.status.textContent = "Không gửi được đáp án: " + e.message;
        }
    }

    async function load() {
        try {
            var res = await window.CandleAuth.authFetch("/api/pattern-quiz/today");
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            state = payload;
            render();
        } catch (e) {
            // Its own failure: the daily round beside it is unaffected and stays playable.
            el.status.textContent = "Không tải được câu hỏi hôm nay: " + e.message;
            el.choices.innerHTML = "";
        }
    }

    window.__initPatternQuizView = load;
})();
