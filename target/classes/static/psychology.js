/**
 * "Tâm Lý Giao Dịch" tab — static tip cards summarizing trading-psychology principles
 * (position sizing, drawdown discipline, process over outcome...), paraphrased in Vietnamese
 * from a public thread by trader Mizer (@MizerXBT) on X, plus a real trade case study he
 * shared. Pure content, no chart/algorithm involved — see index.html for source credit.
 */
(function () {
    "use strict";

    var CASE_STUDY = {
        title: "Case study: lệnh long BTC $40M trước bầu cử Trump",
        body: "30/10/2024, Mizer mở long BTC $40M ở $72,010 với điểm invalidation $64K. Giá giảm về gần $67K khiến uPnL âm khoảng $2.5M, nhưng vì thesis (Trump thắng cử) chưa hề thay đổi nên anh giữ vững lệnh thay vì thoát vì sợ hãi. Kết quả: chốt lời khoảng $11M chỉ hơn 1 tuần sau. Bài học: cùng một lệnh, chỉ có tâm lý người cầm lệnh thay đổi cách nhìn nhận nó — bản thân lệnh không hề đổi khác.",
    };

    function buildCard(tip, index) {
        var card = document.createElement("div");
        card.className = "psych-card";

        var number = document.createElement("span");
        number.className = "psych-card-number";
        number.textContent = String(index + 1).padStart(2, "0");

        var title = document.createElement("h3");
        title.className = "psych-card-title";
        title.textContent = tip.title;

        var body = document.createElement("p");
        body.className = "psych-card-body";
        body.textContent = tip.body;

        card.appendChild(number);
        card.appendChild(title);
        card.appendChild(body);
        return card;
    }

    function buildCaseStudyCard(entry) {
        var card = document.createElement("div");
        card.className = "psych-card psych-case-study";

        var number = document.createElement("span");
        number.className = "psych-card-number";
        number.textContent = "CASE STUDY";

        var title = document.createElement("h3");
        title.className = "psych-card-title";
        title.textContent = entry.title;

        var body = document.createElement("p");
        body.className = "psych-card-body";
        body.textContent = entry.body;

        card.appendChild(number);
        card.appendChild(title);
        card.appendChild(body);
        return card;
    }

    async function init() {
        var grid = document.getElementById("psychology-grid");
        var tips;
        try {
            tips = await window.CandleContent.load("psychology");
        } catch (e) {
            window.CandleContent.notice(grid, "Không tải được ghi chú tâm lý. Thử tải lại trang.");
            return;
        }
        // The case study is a one-off layout rather than a list entry, so it stays in code.
        grid.appendChild(buildCaseStudyCard(CASE_STUDY));
        tips.forEach(function (tip, index) {
            grid.appendChild(buildCard(tip, index));
        });
    }

    /* Built on first reveal, not at load: nothing outside this tab reads it.
       nav.js drives this through its onFirstShow map. */
    window.__initPsychologyView = init;
})();
