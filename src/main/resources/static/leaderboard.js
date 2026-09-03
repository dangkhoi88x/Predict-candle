/**
 * The public leaderboard tab.
 *
 * Nothing is computed here. The server ranks, and this draws — the same division the profile
 * keeps, and for the same reason: two places deciding what a score is would eventually
 * disagree about it.
 *
 * Rebuilt on every reveal rather than once. Ranks move while you play, so a board still showing
 * where you stood when the page loaded is worse than one that takes a moment to arrive.
 */
(function () {
    "use strict";

    var LIMIT = 50;

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function percent(fraction) {
        if (fraction === null || fraction === undefined) return "–";
        return Math.round(fraction * 100) + "%";
    }

    function num(value) {
        return Number(value || 0).toLocaleString("vi-VN");
    }

    function row(entry, isMe) {
        var tr = el("tr", isMe ? "lb-row is-me" : "lb-row");
        tr.appendChild(el("td", "lb-rank", "#" + entry.rank));

        var who = el("td", "lb-name");
        who.appendChild(el("span", null, entry.displayName));
        // Saying which row is yours matters more than any styling can: the pinned copy below
        // is identical otherwise, and without this the two read as two different players.
        if (isMe) who.appendChild(el("span", "lb-you", "bạn"));
        tr.appendChild(who);

        tr.appendChild(el("td", "lb-num", num(entry.score)));
        tr.appendChild(el("td", "lb-num", percent(entry.accuracy)));
        tr.appendChild(el("td", "lb-num", num(entry.correct) + "/" + num(entry.total)));
        tr.appendChild(el("td", "lb-num", num(entry.bestStreak)));
        return tr;
    }

    function table(board) {
        var wrap = el("div", "lb-scroller");
        var t = el("table", "lb-table");

        var head = el("tr");
        ["#", "Người chơi", "Điểm", "Tỉ lệ đúng", "Đúng/Lượt", "Chuỗi"].forEach(function (label, i) {
            var th = el("th", i >= 2 ? "lb-num" : null, label);
            head.appendChild(th);
        });
        t.appendChild(el("thead")).appendChild(head);

        var body = el("tbody");
        var meId = board.me ? board.me.rank : null;
        board.rows.forEach(function (entry) {
            body.appendChild(row(entry, entry.rank === meId));
        });
        t.appendChild(body);
        wrap.appendChild(t);
        return wrap;
    }

    async function init() {
        var container = document.getElementById("leaderboard-body");
        var note = document.getElementById("leaderboard-note");
        if (!container) return;

        try {
            var res = await window.CandleAuth.authFetch("/api/leaderboard?limit=" + LIMIT);
            if (!res.ok) throw new Error("Máy chủ trả về " + res.status);
            var board = await res.json();

            note.textContent = "Từ " + board.minGuesses + " lượt đoán trở lên";
            container.innerHTML = "";

            if (!board.rows.length) {
                window.CandleContent.notice(container,
                    "Chưa có ai đủ " + board.minGuesses + " lượt đoán. Chơi thêm để mở bảng.");
                return;
            }

            container.appendChild(table(board));

            /* Pin the caller's own row when it fell outside the page. Being told "you are 63rd"
               is most of the reason to open this a second time, and a top-50 list alone tells
               everyone outside it nothing at all. */
            if (board.me && !board.rows.some(function (r) { return r.rank === board.me.rank; })) {
                container.appendChild(el("p", "lb-me-label", "Vị trí của bạn"));
                var mine = el("div", "lb-scroller");
                var t = el("table", "lb-table");
                var body = el("tbody");
                body.appendChild(row(board.me, true));
                t.appendChild(body);
                mine.appendChild(t);
                container.appendChild(mine);
            }

            // Signed in but not yet ranked is a different message from an empty board.
            if (!board.me && window.CandleAuth.getUser()) {
                container.appendChild(el("p", "lb-me-label",
                    "Bạn chưa đủ " + board.minGuesses + " lượt đoán để lên bảng."));
            }
        } catch (e) {
            window.CandleContent.notice(container,
                "Không tải được bảng xếp hạng. Mở lại tab này để thử lần nữa.");
        }
    }

    window.__initLeaderboardView = init;
})();
