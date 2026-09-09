/**
 * The signed-in player's own numbers, read straight from /api/stats/me.
 *
 * Nothing is computed here. The scoreboard on the game tab and this page have to agree, and
 * the only way to guarantee that is for neither of them to do arithmetic — the server already
 * settled it. Rendered on each reveal rather than cached, since a round played on the game
 * tab moves these totals.
 */
(function () {
    "use strict";

    var el = {
        wallet: document.getElementById("profile-wallet"),
        score: document.getElementById("profile-score"),
        total: document.getElementById("profile-total"),
        accuracy: document.getElementById("profile-accuracy"),
        best: document.getElementById("profile-best"),
        dayStreak: document.getElementById("profile-day-streak"),
        streakNote: document.getElementById("profile-streak-note"),
        legacyNote: document.getElementById("profile-legacy-note"),
        badges: document.getElementById("profile-badges"),
        byAsset: document.getElementById("profile-by-asset"),
        recent: document.getElementById("profile-recent"),
        shareCard: document.getElementById("profile-share-card"),
        shareSquares: document.getElementById("profile-share-squares"),
        shareLine: document.getElementById("profile-share-line"),
        share: document.getElementById("profile-share"),
        shareText: document.getElementById("profile-share-text"),
    };

    function pct(correct, total) {
        return total === 0 ? "–" : Math.round((correct / total) * 100) + "%";
    }

    function timeAgo(iso) {
        var mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
        if (mins < 1) return "vừa xong";
        if (mins < 60) return mins + " phút trước";
        var hours = Math.round(mins / 60);
        if (hours < 24) return hours + " giờ trước";
        return Math.round(hours / 24) + " ngày trước";
    }

    /* Earned badges first, then the ones still in reach. Ordering by state rather than by the
       catalogue's own order is what makes the section read as "what you have, and what is
       next" instead of a checklist with the wins scattered through it. */
    function renderBadges(rows) {
        el.badges.innerHTML = "";
        if (!rows || !rows.length) return;

        var ordered = rows.slice().sort(function (a, b) {
            if (a.earned !== b.earned) return a.earned ? -1 : 1;
            // Among the unearned, closest to done first — the next goal should be the one at
            // the front, not whichever happens to be cheapest to describe.
            return (b.progress / b.target) - (a.progress / a.target);
        });

        ordered.forEach(function (badge) {
            var card = document.createElement("div");
            card.className = "profile-badge" + (badge.earned ? " is-earned" : "");

            var state = document.createElement("span");
            state.className = "profile-badge-state";
            state.textContent = badge.earned ? "Đã đạt" : "Đang tiến";

            var name = document.createElement("span");
            name.className = "profile-badge-name";
            name.textContent = badge.name;

            var desc = document.createElement("span");
            desc.className = "profile-badge-desc";
            desc.textContent = badge.description;

            card.appendChild(state);
            card.appendChild(name);
            card.appendChild(desc);

            /* An earned badge shows no bar: a full bar beside a finished thing is noise, and
               the point of the bar is the distance still to go. */
            if (!badge.earned) {
                var count = document.createElement("span");
                count.className = "profile-badge-count";
                count.textContent = badge.progress + " / " + badge.target;

                var track = document.createElement("span");
                track.className = "profile-badge-track";
                var fill = document.createElement("span");
                fill.className = "profile-badge-fill";
                fill.style.width = (badge.target === 0 ? 0 : (badge.progress / badge.target) * 100) + "%";
                track.appendChild(fill);

                card.appendChild(count);
                card.appendChild(track);
            }

            el.badges.appendChild(card);
        });
    }

    function renderByAsset(rows) {
        el.byAsset.innerHTML = "";
        if (!rows.length) {
            el.byAsset.innerHTML = '<p class="profile-empty">Chưa có lượt đoán nào được ghi lại.</p>';
            return;
        }
        rows.forEach(function (row) {
            var card = document.createElement("div");
            card.className = "profile-asset";

            var name = document.createElement("span");
            name.className = "profile-asset-name";
            name.textContent = row.symbol;

            var rate = document.createElement("span");
            rate.className = "profile-asset-rate rolling";
            window.CandleRolling.update(rate, pct(row.correct, row.total));

            var count = document.createElement("span");
            count.className = "profile-asset-count";
            count.textContent = row.correct + "/" + row.total;

            // A bar reads at a glance in a way three numbers in a row do not.
            var track = document.createElement("span");
            track.className = "profile-asset-track";
            var fill = document.createElement("span");
            fill.className = "profile-asset-fill";
            fill.style.width = (row.total === 0 ? 0 : (row.correct / row.total) * 100) + "%";
            track.appendChild(fill);

            card.appendChild(name);
            card.appendChild(rate);
            card.appendChild(count);
            card.appendChild(track);
            el.byAsset.appendChild(card);
        });
    }

    function renderRecent(rows) {
        el.recent.innerHTML = "";
        if (!rows.length) {
            el.recent.innerHTML = '<p class="profile-empty">Lịch sử sẽ xuất hiện sau lượt đoán đầu tiên.</p>';
            return;
        }
        rows.forEach(function (row) {
            var item = document.createElement("div");
            item.className = "profile-guess " + (row.correct ? "is-correct" : "is-wrong");

            var mark = document.createElement("span");
            mark.className = "profile-guess-mark";
            mark.textContent = row.correct ? "✓" : "✕";

            var symbol = document.createElement("span");
            symbol.className = "profile-guess-symbol";
            symbol.textContent = row.symbol;

            var call = document.createElement("span");
            call.className = "profile-guess-call";
            // Show what was actually right too, so a miss says why it was a miss. A guess with
            // no direction is one the countdown ate — saying "đoán null" would be worse than
            // saying nothing, and calling it a wrong guess would be untrue.
            call.textContent = row.guessed
                ? (row.correct ? "đoán " + row.guessed
                               : "đoán " + row.guessed + " · thực tế " + row.actual)
                : "hết giờ · thực tế " + row.actual;

            var when = document.createElement("span");
            when.className = "profile-guess-when";
            when.textContent = timeAgo(row.at);

            item.appendChild(mark);
            item.appendChild(symbol);
            item.appendChild(call);
            item.appendChild(when);
            el.recent.appendChild(item);
        });
    }

    /* The streak the player can still act on today. A run that is alive but unplayed is the
       only state worth a line of its own — it is the one moment where opening the game changes
       the number, and saying so is the whole point of counting days. */
    function renderDayStreak(streak) {
        window.CandleRolling.update(el.dayStreak, streak.current);
        el.dayStreak.title = "Dài nhất " + streak.best + " ngày · đã chơi " + streak.daysPlayed + " ngày";

        var atRisk = streak.current > 0 && !streak.playedToday;
        el.streakNote.classList.toggle("hidden", !atRisk);
        if (atRisk) {
            el.streakNote.textContent =
                "Chuỗi " + streak.current + " ngày của bạn vẫn đang mở. Chơi một ván hôm nay để giữ nó.";
        }
    }

    /* ---- the share card ------------------------------------------------------------
       Standing rather than a round: score, accuracy, day streak, and the last five calls as
       squares. It follows the daily card's rule of carrying no asset and no dates, though
       for a different reason — there is no puzzle to spoil here, only a record to post, and
       naming the pairs would say which markets someone plays without adding anything.

       Recorded results only. A carried-over browser tally has no per-guess detail behind it,
       so squares drawn from it would be invented. */

    /* The text the button copies, rebuilt whenever the numbers behind it are. */
    var shareBody = "";

    function renderShare(data) {
        var results = (data.recent || []).slice(0, 5).map(function (g) { return g.correct; }).reverse();
        el.shareCard.classList.toggle("hidden", !results.length);
        if (!results.length) return;

        el.shareSquares.textContent = results
            .map(function (ok) { return ok ? "🟩" : "🟥"; }).join("");
        el.shareLine.textContent =
            data.score + " điểm · " + pct(data.correct, data.total) + " đúng · "
            + data.dayStreak.current + " ngày liên tiếp";
        shareBody = "Candle Guess — hồ sơ\n"
            + el.shareLine.textContent + "\n"
            + el.shareSquares.textContent + "\n"
            + window.location.origin;
    }

    async function copyShare() {
        try {
            await navigator.clipboard.writeText(shareBody);
            el.share.textContent = "Đã sao chép";
            setTimeout(function () { el.share.textContent = "Sao chép kết quả"; }, 2000);
        } catch (e) {
            /* Refused — an insecure origin, or a browser that wants a gesture it did not see.
               Putting the text on screen and selecting it is the difference between a share
               button that failed and one the player can still act on. */
            el.shareText.value = shareBody;
            el.shareText.classList.remove("hidden");
            el.shareText.select();
            el.share.textContent = "Chép thủ công ở dưới";
        }
    }

    function render(data) {
        var user = window.CandleAuth.getUser();
        el.wallet.textContent = user ? user.displayName : "";

        window.CandleRolling.update(el.score, data.score);
        window.CandleRolling.update(el.total, data.total);
        window.CandleRolling.update(el.accuracy, pct(data.correct, data.total));
        window.CandleRolling.update(el.best, data.bestStreak);

        renderDayStreak(data.dayStreak);

        /* Say plainly which part of the total the server watched happen. Without this the
           carried-over figures look like they were all earned on this account. */
        var carried = data.total - data.recorded.total;
        el.legacyNote.classList.toggle("hidden", !data.legacyImported || carried <= 0);
        if (data.legacyImported && carried > 0) {
            el.legacyNote.textContent =
                "Trong đó " + carried + " lượt được mang lên từ dữ liệu lưu trên trình duyệt trước khi bạn "
                + "có tài khoản. " + data.recorded.total + " lượt được máy chủ ghi lại.";
        }

        renderBadges(data.achievements);
        renderByAsset(data.byAsset);
        renderRecent(data.recent);
        renderShare(data);
    }

    var loaded = false;

    /* On a first open that fails there is nothing on screen to fall back to, and the two
       list sections would sit empty with no explanation — indistinguishable from an account
       that has never played. Say so instead. */
    function showLoadFailure() {
        var message = '<p class="profile-empty">Không tải được thống kê. Kiểm tra kết nối rồi mở lại tab này.</p>';
        el.byAsset.innerHTML = message;
        el.recent.innerHTML = message;
    }

    async function load() {
        if (!window.CandleAuth.getUser()) return;
        try {
            var res = await window.CandleAuth.authFetch("/api/stats/me");
            if (res.ok) {
                render(await res.json());
                loaded = true;
                return;
            }
        } catch (e) {
            // Falls through to the same handling as a non-ok response.
        }
        // Once something has rendered, leaving it up beats replacing real numbers with an
        // error the player can do nothing about.
        if (!loaded) showLoadFailure();
    }

    el.share.addEventListener("click", copyShare);

    window.__initProfileView = load;
})();
