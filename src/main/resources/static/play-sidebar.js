(function () {
    "use strict";

    /* The column beside the chart: how the session is going, whether today's challenge is
       still unplayed, which badge is nearest, and who is ahead.

       None of it is computed here. The session figures and the badges ride in on the
       `candles:stats` event app.js publishes from the /api/stats/me response it already
       fetches after every recorded guess — asking that endpoint a second time from this file
       would buy nothing and give the page two answers to the same question. The board and
       today's challenge are the two things app.js does not ask for, so those are fetched
       here, once, and re-fetched when the session changes. */

    var DOTS = 10;

    var el = {
        dots: document.getElementById("side-dots"),
        dotsLabel: document.getElementById("side-dots-label"),
        daily: document.getElementById("side-daily"),
        dailyTitle: document.getElementById("side-daily-title"),
        badges: document.getElementById("side-badges"),
        badgesList: document.getElementById("side-badges-list"),
        rank: document.getElementById("side-rank"),
        rankList: document.getElementById("side-rank-list"),
    };

    /* Oldest first, so a new call arrives on the right and the strip reads the way the
       session ran. Trimmed from the front, never the back. */
    var recent = [];

    function node(tag, className, text) {
        var n = document.createElement(tag);
        if (className) n.className = className;
        if (text !== undefined) n.textContent = text;
        return n;
    }

    function num(value) {
        return Number(value || 0).toLocaleString("vi-VN");
    }

    /* ---- recent calls ------------------------------------------------------------------ */

    function renderDots() {
        el.dots.innerHTML = "";
        for (var i = 0; i < DOTS; i++) {
            var got = recent[recent.length - DOTS + i];
            var cls = "side-dot";
            if (got === true) cls += " is-correct";
            else if (got === false) cls += " is-wrong";
            el.dots.appendChild(node("span", cls));
        }
        el.dotsLabel.textContent = recent.length
            ? Math.min(recent.length, DOTS) + " lượt gần nhất"
            : "Chưa có lượt nào";
    }

    /* ---- today's challenge -------------------------------------------------------------- */

    function renderDaily(round) {
        if (!round) {
            /* The card is an invitation, so it stays — a player who cannot be told the round
               number can still be told there is one. */
            el.dailyTitle.textContent = "Thử thách hôm nay";
            return;
        }
        var done = round.completed;
        el.dailyTitle.textContent = "Vòng #" + round.roundNumber + (done ? " đã xong" : " chưa chơi");
        el.daily.querySelector(".side-cta").textContent = done ? "Xem lại" : "Chơi ngay";
    }

    function loadDaily() {
        window.CandleAuth.authFetch("/api/daily/round")
            .then(function (res) {
                if (!res.ok) throw new Error("daily unavailable");
                return res.json();
            })
            .then(renderDaily)
            .catch(function () { renderDaily(null); });
    }

    /* ---- badges --------------------------------------------------------------------------
       Three unearned badges, nearest first. Earned ones are left to the profile: this card
       exists to name the next thing within reach, and a wall of trophies already collected
       says nothing about what to do with the round on screen. */

    function renderBadges(achievements) {
        var near = (achievements || [])
            .filter(function (b) { return !b.earned && b.target > 0; })
            .sort(function (a, b) { return (b.progress / b.target) - (a.progress / a.target); })
            .slice(0, 3);

        el.badges.classList.toggle("hidden", !near.length);
        el.badgesList.innerHTML = "";
        near.forEach(function (badge) {
            var wrap = node("div");
            wrap.title = badge.description;

            var head = node("div", "side-badge-head");
            head.appendChild(node("b", "side-badge-name", badge.name));
            head.appendChild(node("span", "side-badge-count",
                num(badge.progress) + " / " + num(badge.target)));
            wrap.appendChild(head);

            var bar = node("span", "side-bar");
            var fill = node("span", "side-bar-fill");
            fill.style.width = Math.min(100, (badge.progress / badge.target) * 100) + "%";
            bar.appendChild(fill);
            wrap.appendChild(bar);

            el.badgesList.appendChild(wrap);
        });
    }

    /* ---- the board ----------------------------------------------------------------------- */

    function rankRow(entry, isMe) {
        var row = node("div", isMe ? "side-rank-row is-me" : "side-rank-row");
        row.appendChild(node("span", "side-rank-pos", "#" + entry.rank));
        /* Shared with the leaderboard tab: the same player has to have the same face on both,
           or the two boards read as two sets of people. */
        row.appendChild(window.CandleAvatar.node(entry.displayName));
        row.appendChild(node("span", "side-rank-name", entry.displayName));
        row.appendChild(node("span", "side-rank-score", num(entry.score)));
        return row;
    }

    /* The rail wants to show the caller's rank beside "Bảng Xếp Hạng", and this module is
       already asking the endpoint that knows it. Publishing beats a second fetch for the same
       reason `candles:stats` exists — two callers reading one figure out of two responses can
       only end up disagreeing about it. Null means "no rank to show", which the rail draws as
       no tag rather than as a blank one. */
    function publishRank(rank) {
        document.dispatchEvent(new CustomEvent("candles:rank", { detail: { rank: rank } }));
    }

    function loadRank() {
        /* Five rows, not fifty: this is a glance at who is ahead, and "Xem tất cả" is right
           there for the rest. */
        window.CandleAuth.authFetch("/api/leaderboard?limit=5")
            .then(function (res) {
                if (!res.ok) throw new Error("leaderboard unavailable");
                return res.json();
            })
            .then(function (board) {
                if (!board.rows.length) {
                    el.rank.classList.add("hidden");
                    publishRank(null);
                    return;
                }
                var meRank = board.me ? board.me.rank : null;
                publishRank(meRank);
                el.rankList.innerHTML = "";
                board.rows.forEach(function (entry) {
                    el.rankList.appendChild(rankRow(entry, entry.rank === meRank));
                });
                /* Ranked but off the bottom of the five: the row is pinned under them, the
                   same bargain the full board makes. Being told where you actually stand is
                   most of the reason to look at a board at all. */
                if (board.me && meRank > board.rows.length) {
                    el.rankList.appendChild(rankRow(board.me, true));
                }
                el.rank.classList.remove("hidden");
            })
            .catch(function () {
                // An empty card says less than no card. Leave it out rather than draw a gap.
                el.rank.classList.add("hidden");
                publishRank(null);
            });
    }

    /* ---- wiring ---------------------------------------------------------------------------- */

    document.addEventListener("candles:stats", function (event) {
        var data = event.detail;
        if (!data) {
            // Signed out: the badges belong to the account, the strip belongs to the browser.
            renderBadges([]);
            return;
        }
        renderBadges(data.achievements);
        /* recent is newest first from the server; the strip runs oldest to newest, and only
           the last ten of it fit. Replaces rather than merges — the server's list already
           includes the guesses this session made. */
        recent = (data.recent || []).slice(0, DOTS).map(function (g) { return g.correct; }).reverse();
        renderDots();
    });

    document.addEventListener("candles:guess", function (event) {
        recent.push(event.detail.correct);
        if (recent.length > DOTS) recent = recent.slice(-DOTS);
        renderDots();
    });

    document.addEventListener("candles:session", function () {
        // Both of these read differently for an account: the board gains a "me" row, and the
        // challenge gains whether today has been played.
        loadDaily();
        loadRank();
    });

    renderDots();
    loadDaily();
    loadRank();
})();
