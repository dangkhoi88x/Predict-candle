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

    /* Where the caller's own row is pinned. Being told you are 63rd is most of the reason to
       open this a second time, and at the bottom of fifty rows that answer is invisible —
       which is where it used to sit. Under the top five it is the first thing after the
       podium, which is the one place a reader is guaranteed to look. */
    var PIN_AFTER = 5;

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

    /* `pinned` marks the row lifted up under the podium. It is the only place that player
       appears — the row is moved, not duplicated — so it carries a badge to explain why the
       numbering jumps around it. */
    function row(entry, isMe, pinned) {
        var tr = el("tr", "lb-row" + (isMe ? " is-me" : "") + (pinned ? " is-pinned" : ""));
        tr.appendChild(el("td", "lb-rank", "" + entry.rank));

        var who = el("td", "lb-name");
        var cell = el("div", "lb-who");
        cell.appendChild(window.CandleAvatar.node(entry.displayName));
        /* Only the lifted copy renames itself: the row in its own rank keeps the name
           everyone else on the board sees you by, and loses it if this said "Bạn" there. */
        cell.appendChild(el("span", "lb-who-name", pinned ? "Bạn" : entry.displayName));
        if (pinned) {
            cell.appendChild(el("span", "lb-you", "Hạng của bạn"));
            cell.title = entry.displayName;
        }
        who.appendChild(cell);
        tr.appendChild(who);

        tr.appendChild(el("td", "lb-num lb-score", num(entry.score)));
        tr.appendChild(el("td", "lb-num lb-accuracy", percent(entry.accuracy)));
        tr.appendChild(el("td", "lb-num lb-muted", num(entry.correct) + "/" + num(entry.total)));
        tr.appendChild(el("td", "lb-num lb-score", num(entry.bestStreak)));
        return tr;
    }

    var COLUMNS = ["#", "Người chơi", "Điểm", "Tỉ lệ đúng", "Đúng/Lượt", "Chuỗi"];

    function table(board) {
        var wrap = el("div", "lb-scroller");
        var t = el("table", "lb-table");

        var head = el("tr");
        COLUMNS.forEach(function (label, i) {
            head.appendChild(el("th", i >= 2 ? "lb-num" : null, label));
        });
        t.appendChild(el("thead")).appendChild(head);

        var body = el("tbody");
        var meRank = board.me ? board.me.rank : null;
        var pinning = !!board.me && meRank > PIN_AFTER;
        board.rows.forEach(function (entry, index) {
            /* Lifted, not copied. The first version drew the pinned row and left the original
               where it was, which is fine at rank 63 and unreadable at rank 6 — two adjacent
               rows with the same rank, name and score look like a rendering bug, not like a
               board being helpful. Moving it leaves a gap in the numbering instead, and the
               badge on the pinned row is what explains the gap. */
            if (pinning && entry.rank === meRank) return;
            body.appendChild(row(entry, entry.rank === meRank, false));
            /* Pinned after the podium whenever the caller sits outside it — including when
               they are outside the returned page entirely, which is the case the old
               bottom-of-the-table copy existed for. */
            if (index === PIN_AFTER - 1 && pinning) {
                body.appendChild(row(board.me, true, true));
            }
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
