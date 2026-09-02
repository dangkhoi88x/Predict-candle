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
        legacyNote: document.getElementById("profile-legacy-note"),
        byAsset: document.getElementById("profile-by-asset"),
        recent: document.getElementById("profile-recent"),
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
            // Show what was actually right too, so a miss says why it was a miss.
            call.textContent = row.correct
                ? "đoán " + row.guessed
                : "đoán " + row.guessed + " · thực tế " + row.actual;

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

    function render(data) {
        var user = window.CandleAuth.getUser();
        el.wallet.textContent = user ? user.displayName : "";

        window.CandleRolling.update(el.score, data.score);
        window.CandleRolling.update(el.total, data.total);
        window.CandleRolling.update(el.accuracy, pct(data.correct, data.total));
        window.CandleRolling.update(el.best, data.bestStreak);

        /* Say plainly which part of the total the server watched happen. Without this the
           carried-over figures look like they were all earned on this account. */
        var carried = data.total - data.recorded.total;
        el.legacyNote.classList.toggle("hidden", !data.legacyImported || carried <= 0);
        if (data.legacyImported && carried > 0) {
            el.legacyNote.textContent =
                "Trong đó " + carried + " lượt được mang lên từ dữ liệu lưu trên trình duyệt trước khi bạn "
                + "có tài khoản. " + data.recorded.total + " lượt được máy chủ ghi lại.";
        }

        renderByAsset(data.byAsset);
        renderRecent(data.recent);
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

    window.__initProfileView = load;
})();
