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

    /* Which window is on screen: a month id, or "all". Null until the first response says which
       season is running — the page asks for no season at all and lets the server decide, so a
       link somebody kept from last month still opens on this one. */
    var season = null;

    /* The last season the server named. All-time answers with no season at all, so without this
       the picker would lose both month options the moment somebody pressed "Mọi lúc" — and there
       would be no way back to them. */
    var knownSeason = null;

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

        tr.appendChild(el("td", "lb-num lb-score", window.CandleFormat.count(entry.score)));
        tr.appendChild(el("td", "lb-num lb-accuracy", window.CandleFormat.percent(entry.accuracy)));
        tr.appendChild(el("td", "lb-num lb-muted", window.CandleFormat.count(entry.correct) + "/" + window.CandleFormat.count(entry.total)));
        tr.appendChild(el("td", "lb-num lb-score", window.CandleFormat.count(entry.bestStreak)));
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

    /* An empty board is the first thing a new site's leaderboard shows, so it says what it takes
       to be on it and offers the way there, rather than only that nobody is. Signed out it also
       says the part a visitor would not guess: anonymous play is never recorded, so no amount of
       it reaches the board. */
    /* A month with nobody on it yet is the ordinary state of a season's first days, and it reads
       as a broken board unless it says which month it is empty for. */
    function emptyBoard(container, minGuesses, seasonInfo) {
        container.innerHTML = "";
        var box = el("div", "lb-empty");
        var running = seasonInfo && seasonInfo.current;
        box.appendChild(el("p", "lb-empty-title", running ? "Mùa này đang chờ người đầu tiên"
            : seasonInfo ? seasonInfo.label + " không có ai lên bảng" : "Bảng đang chờ người đầu tiên"));
        box.appendChild(el("p", "lb-empty-text", "Chưa ai đủ " + minGuesses
            + " lượt đoán được ghi lại" + (seasonInfo ? " trong " + seasonInfo.label.toLowerCase() : "")
            + ". Người đầu tiên đạt " + minGuesses + " lượt sẽ đứng hạng #1."
            + (window.CandleAuth.getUser() ? "" : " Kết nối ví hoặc email để lượt đoán của bạn được tính.")));
        var play = el("button", "side-cta lb-empty-cta", "Chơi ngay");
        play.type = "button";
        play.setAttribute("data-nav-view", "game");
        box.appendChild(play);
        container.appendChild(box);
    }

    /** Days left in a running season — the reason to play this week rather than next month. */
    function daysLeft(endsAt) {
        var left = Math.ceil((new Date(endsAt).getTime() - Date.now()) / 86400000);
        return left > 1 ? "còn " + left + " ngày" : "ngày cuối";
    }

    /* Three at most: this month, the month before it, and everything. A full archive of seasons
       is a list that grows forever for a page nobody scrolls back through — the month before is
       the one anybody asks about, and "Mọi lúc" is where a long history still counts. */
    function renderSeasons() {
        var track = document.getElementById("leaderboard-seasons");
        if (!track) return;
        var options = [];
        if (knownSeason) {
            options.push({ id: knownSeason.id, label: knownSeason.current ? "Tháng này" : knownSeason.label });
            if (knownSeason.previousId) {
                options.push({ id: knownSeason.previousId, label: "Tháng trước" });
            }
        }
        options.push({ id: "all", label: "Mọi lúc" });

        track.innerHTML = "";
        options.forEach(function (option) {
            var button = el("button", "pill-option" + (option.id === season ? " active" : ""), option.label);
            button.type = "button";
            button.addEventListener("click", function () {
                if (option.id === season) return;
                season = option.id;
                init();
            });
            track.appendChild(button);
        });
    }

    async function init() {
        var container = document.getElementById("leaderboard-body");
        var note = document.getElementById("leaderboard-note");
        if (!container) return;

        try {
            var res = await window.CandleAuth.authFetch("/api/leaderboard?limit=" + LIMIT
                + (season ? "&season=" + encodeURIComponent(season) : ""));
            if (!res.ok) throw new Error("Máy chủ trả về " + res.status);
            var board = await res.json();
            season = board.season ? board.season.id : "all";
            /* Only a month answer updates the picker's months: pressing "Tháng trước" must not
               make that month the one "Tháng này" points at. */
            if (board.season && board.season.current) knownSeason = board.season;
            else if (board.season && !knownSeason) knownSeason = { id: board.season.id, label: board.season.label, current: false, previousId: board.season.previousId };

            note.textContent = (board.season
                ? board.season.label + (board.season.current ? " · " + daysLeft(board.season.endsAt) : " · đã kết thúc")
                : "Mọi lúc") + " · từ " + board.minGuesses + " lượt đoán trở lên";
            renderSeasons();
            container.innerHTML = "";

            if (!board.rows.length) {
                emptyBoard(container, board.minGuesses, board.season);
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
