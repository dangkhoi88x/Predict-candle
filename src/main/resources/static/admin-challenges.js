/**
 * What the site is about to ask, day by day.
 *
 * Both the daily chart and the pattern quiz are functions of the date and neither stores
 * anything, so there is nothing to edit here and nothing to inspect anywhere else. The page
 * exists for the failure the two selectors share: either can refuse to pick, and either refusal
 * breaks a whole day of the site until the next midnight. On this list that is a message on a
 * row instead of a 500 in front of a player.
 *
 * A day still to come is a rehearsal, not a promise — its chart is drawn from the candles that
 * will have closed before its own midnight, and that count is still rising. The rows say so.
 */
(function () {
    "use strict";

    var el = {
        section: document.getElementById("admin-challenges"),
        today: document.getElementById("challenge-today"),
        refresh: document.getElementById("challenge-refresh"),
        rows: document.querySelector("#challenge-table tbody"),
        detail: document.getElementById("challenge-detail"),
        detailTitle: document.getElementById("challenge-detail-title"),
        detailSub: document.getElementById("challenge-detail-sub"),
        detailClose: document.getElementById("challenge-detail-close"),
        dailyNote: document.getElementById("challenge-daily-note"),
        dailyChart: document.getElementById("challenge-daily-chart"),
        dailyAnswers: document.getElementById("challenge-daily-answers"),
        quizNote: document.getElementById("challenge-quiz-note"),
        quizChart: document.getElementById("challenge-quiz-chart"),
        quizChoices: document.getElementById("challenge-quiz-choices"),
        status: document.getElementById("admin-status"),
    };
    if (!el.section) return;

    /* ---- the chart renderer, loaded on demand ---- */

    /* candle-chart.js is 28 KB and only an admin who opens a day's detail ever needs it, so
       admin.html carries no tag for it — the same bargain admin-blog.js makes with the Tiptap
       bundle, on a page whose weight this project spent a release cutting. Loading starts when
       a detail is opened and the draw waits on the same promise. */
    var chartLoader = null;

    function loadChart() {
        if (window.CandleChart) return Promise.resolve(window.CandleChart);
        if (chartLoader) return chartLoader;

        chartLoader = new Promise(function (resolve, reject) {
            var script = document.createElement("script");
            script.src = "candle-chart.js";
            script.onload = function () {
                if (window.CandleChart) resolve(window.CandleChart);
                else reject(new Error("Bộ vẽ biểu đồ tải xong nhưng không khởi tạo được."));
            };
            script.onerror = function () {
                chartLoader = null;
                reject(new Error("Không tải được bộ vẽ biểu đồ."));
            };
            document.head.appendChild(script);
        });
        return chartLoader;
    }

    /* ---- formatting ---- */

    function date(iso) {
        var parts = String(iso).split("-");
        return parts[2] + "/" + parts[1];
    }

    function count(value) {
        return Number(value || 0).toLocaleString("vi-VN");
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    function cell(row, text, className) {
        row.appendChild(element("td", className, text));
    }

    async function api(path) {
        var res = await window.CandleAuth.authFetch("/api/admin/challenges" + path);
        var payload = await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    /* ---- the list ---- */

    /**
     * Three states, and only one of them is a problem. A day that cannot be built is the row
     * this page exists for; a provisional day is normal and says so; everything else is ready.
     */
    function statusBadge(day) {
        var broken = (day.daily && day.daily.problem) || (day.quiz && day.quiz.problem);
        if (broken) return element("span", "ops-badge is-bad", "không dựng được");
        if (day.provisional) return element("span", "ops-badge is-off", "dự kiến");
        return element("span", "ops-badge is-good", day.isToday ? "hôm nay" : "đã chạy");
    }

    function render(data) {
        el.today.textContent = "Hôm nay " + data.today;
        el.rows.innerHTML = "";

        data.days.forEach(function (day) {
            var tr = element("tr");
            cell(tr, date(day.day), "num");
            cell(tr, "#" + day.roundNumber, "num");

            // A selector that refused says why in its own words; both throw with a sentence
            // written for exactly this moment.
            cell(tr, day.daily.problem ? day.daily.problem : (day.daily.asset || "—"));

            var quizText = day.quiz.problem ? day.quiz.problem
                : day.quiz.patternId ? (day.quiz.patternName || day.quiz.patternId)
                : "chưa ai trả lời";
            var quiz = element("td", null, quizText);
            if (day.quiz.resolved === "answers") {
                // Read back from an answer rather than rebuilt: worth saying, because it is why
                // there is no chart for this row's quiz.
                quiz.title = "Đọc lại từ câu trả lời đã ghi, không dựng lại câu hỏi.";
            }
            tr.appendChild(quiz);

            var played = day.participation
                ? count(day.participation.dailyPlayers) + " · " + count(day.participation.quizAnswers)
                : "—";
            var playedCell = element("td", "num", played);
            if (day.participation) {
                playedCell.title = "Biểu đồ ngày: " + count(day.participation.dailyPlayers)
                    + " người, " + count(day.participation.dailyGuesses) + " lượt, "
                    + count(day.participation.dailyCorrect) + " đúng · Câu đố: "
                    + count(day.participation.quizAnswers) + " trả lời, "
                    + count(day.participation.quizCorrect) + " đúng";
            }
            tr.appendChild(playedCell);

            var status = element("td");
            status.appendChild(statusBadge(day));
            tr.appendChild(status);

            var actions = element("td", "asset-actions");
            actions.appendChild(action("Xem", function () { openDetail(day); }));
            tr.appendChild(actions);

            if (day.isToday) tr.classList.add("is-today");
            el.rows.appendChild(tr);
        });
    }

    function action(label, onClick) {
        var b = element("button", "ghost-btn", label);
        b.type = "button";
        b.addEventListener("click", onClick);
        return b;
    }

    /* ---- one day ---- */

    async function openDetail(day) {
        try {
            var both = await Promise.all([api("/" + day.day), loadChart()]);
            renderDetail(both[0]);
        } catch (e) {
            el.status.textContent = "Không mở được ngày " + day.day + ": " + e.message;
        }
    }

    function clear(svg) {
        while (svg.firstChild) svg.removeChild(svg.firstChild);
    }

    function renderDetail(detail) {
        el.detail.classList.remove("hidden");
        el.detailTitle.textContent = "Ngày " + detail.day + " · vòng #" + detail.roundNumber;
        el.detailSub.textContent = detail.provisional
            ? "Dự kiến — kho nến còn dài ra tới nửa đêm hôm đó nên lượt chọn còn dịch"
            : "Đã cố định";

        renderDaily(detail.daily);
        renderQuiz(detail.quiz);
    }

    function renderDaily(daily) {
        if (daily.problem) {
            clear(el.dailyChart);
            el.dailyNote.textContent = "không dựng được";
            el.dailyAnswers.textContent = daily.problem;
            return;
        }

        el.dailyNote.textContent = daily.asset + " · " + daily.timeframe + " · index " + daily.startIndex;

        /* The visible window and the answers in one picture, with the answers marked: whether
           tomorrow is a coin flip is a question about the candles the player never sees, and it
           cannot be asked from the visible ones alone. */
        var candles = daily.visible.concat(daily.answers.map(function (a) { return a.candle; }));
        window.CandleChart.draw(el.dailyChart, candles, {
            highlights: [{ from: daily.visible.length, length: daily.answers.length }],
        });

        var directions = daily.answers.map(function (a) { return a.direction === "LONG" ? "L" : "S"; });
        var longs = directions.filter(function (d) { return d === "L"; }).length;
        el.dailyAnswers.textContent = "Đáp án theo thứ tự: " + directions.join(" ")
            + " — " + longs + " tăng / " + (directions.length - longs) + " giảm.";
    }

    function renderQuiz(quiz) {
        if (quiz.problem) {
            clear(el.quizChart);
            el.quizNote.textContent = "không dựng được";
            el.quizChoices.textContent = quiz.problem;
            return;
        }

        el.quizNote.textContent = (quiz.patternName || quiz.patternId) + " · " + quiz.asset;
        window.CandleChart.draw(el.quizChart, quiz.candles, {
            highlight: { from: quiz.patternStartIndex, length: quiz.patternLength },
        });
        el.quizChoices.textContent = "Bốn lựa chọn: " + quiz.choices.join(", ")
            + " — đáp án là " + quiz.patternId + ".";
    }

    function closeDetail() {
        el.detail.classList.add("hidden");
        clear(el.dailyChart);
        clear(el.quizChart);
    }

    /* ---- loading ---- */

    async function load(fresh) {
        try {
            render(await api(fresh ? "?fresh=true" : ""));
        } catch (e) {
            el.status.textContent = "Không tải được lịch thử thách: " + e.message;
        }
    }

    el.refresh.addEventListener("click", function () { load(true); });
    el.detailClose.addEventListener("click", closeDetail);

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load(false);
    });
})();
