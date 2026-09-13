/**
 * Accounts: one page at a time, and one account in detail.
 *
 * The role column is shown without a control beside it because roles come from
 * candles.admin.wallets and anything set here would be undone on restart. The detail view keeps
 * the same bargain — it reads the rows the game scores on and offers nothing to change them
 * with. The two actions are still the two an admin legitimately has: fix a name, delete on
 * request.
 */
(function () {
    "use strict";

    var PAGE_SIZE = 50;
    var SEARCH_DEBOUNCE_MS = 250;

    var el = {
        section: document.getElementById("admin-players"),
        count: document.getElementById("players-count"),
        search: document.getElementById("players-search"),
        sort: document.getElementById("players-sort"),
        rows: document.querySelector("#player-table tbody"),
        pager: document.getElementById("players-pager"),
        prev: document.getElementById("players-prev"),
        next: document.getElementById("players-next"),
        range: document.getElementById("players-range"),
        detail: document.getElementById("player-detail"),
        detailTitle: document.getElementById("player-detail-title"),
        detailSub: document.getElementById("player-detail-sub"),
        detailClose: document.getElementById("player-detail-close"),
        figures: document.getElementById("player-detail-figures"),
        legacy: document.getElementById("player-detail-legacy"),
        guesses: document.querySelector("#player-guesses tbody"),
        calls: document.querySelector("#player-calls tbody"),
        status: document.getElementById("admin-status"),
    };
    if (!el.section) return;

    var query = "";
    var sort = "active";
    var page = 0;
    var searchTimer = null;

    function shortWallet(address) {
        return address.slice(0, 6) + "…" + address.slice(-4);
    }

    function when(iso) {
        if (!iso) return "chưa chơi";
        var days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
        if (days === 0) return "hôm nay";
        if (days === 1) return "hôm qua";
        return days + " ngày trước";
    }

    function clock(iso) {
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    function pct(correct, total) {
        return total ? Math.round((correct / total) * 100) + "%" : "—";
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

    function side(direction) {
        // A guess that ran out of time has no direction to draw, and picking one would be a lie.
        if (!direction) return element("span", "ops-badge is-off", "hết giờ");
        return element("span", "ops-badge " + (direction === "LONG" ? "is-long" : "is-short"), direction);
    }

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/players" + path, options);
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    /* ---- the list ---- */

    function render(data) {
        var from = data.total === 0 ? 0 : data.page * data.size + 1;
        var to = data.page * data.size + data.players.length;

        el.count.textContent = data.total + (data.query ? " tài khoản khớp" : " tài khoản");
        el.range.textContent = data.total === 0 ? "không có kết quả" : from + "–" + to + " / " + data.total;
        el.prev.disabled = data.page === 0;
        el.next.disabled = !data.hasMore;

        el.rows.innerHTML = "";
        if (!data.players.length) {
            var empty = element("tr");
            var td = element("td", null, data.query
                ? "Không có tài khoản nào khớp “" + data.query + "”."
                : "Chưa có tài khoản nào.");
            td.colSpan = 7;
            empty.appendChild(td);
            el.rows.appendChild(empty);
            return;
        }

        data.players.forEach(function (player) {
            var tr = element("tr");

            var wallet = element("td", "num", shortWallet(player.walletAddress));
            wallet.title = player.walletAddress;
            tr.appendChild(wallet);

            cell(tr, player.displayName);

            var role = element("td");
            role.appendChild(element("span",
                "ops-badge " + (player.role === "ADMIN" ? "is-admin" : "is-off"), player.role));
            tr.appendChild(role);

            cell(tr, player.guesses, "num");
            cell(tr, pct(player.correct, player.guesses), "num");
            cell(tr, when(player.lastPlayedAt));

            var actions = element("td", "asset-actions");
            actions.appendChild(action("Chi tiết", function () { openDetail(player); }));
            actions.appendChild(action("Đổi tên", function () { rename(player); }));
            if (player.role !== "ADMIN") {
                actions.appendChild(action("Xoá", function () { remove(player); }, "danger-btn"));
            }
            tr.appendChild(actions);

            el.rows.appendChild(tr);
        });
    }

    function action(label, onClick, cls) {
        var b = element("button", cls || "ghost-btn", label);
        b.type = "button";
        b.addEventListener("click", onClick);
        return b;
    }

    /* ---- one account ---- */

    async function openDetail(player) {
        try {
            renderDetail(await api("/" + player.id));
        } catch (e) {
            el.status.textContent = "Không đọc được tài khoản: " + e.message;
        }
    }

    function figure(value, note, base) {
        var box = element("div", "adm-figure");
        box.appendChild(element("b", "adm-mini-value", value));
        box.appendChild(element("span", "adm-mini-note", note));
        box.appendChild(element("span", "adm-figure-base", base));
        return box;
    }

    function renderDetail(detail) {
        el.detail.classList.remove("hidden");
        el.detailTitle.textContent = detail.account.displayName;
        el.detailSub.textContent = shortWallet(detail.account.walletAddress)
            + " · mở tài khoản " + clock(detail.createdAt);

        el.figures.innerHTML = "";
        el.figures.appendChild(figure(detail.account.guesses, "lượt đoán đã ghi",
            pct(detail.account.correct, detail.account.guesses) + " đúng"));
        el.figures.appendChild(figure(detail.live.calls, "lượt gọi live",
            // Accuracy on live calls is against settled ones: a round still running is not a
            // round the player got wrong.
            detail.live.settled + " đã chốt · " + pct(detail.live.correct, detail.live.settled) + " đúng"));

        /* The two splits go in the footnote rather than in a third figure. `.adm-mini-value` is
           26px mono sized for a number; a run of "PRACTICE 500 · DAILY 120 · ARCHIVE 22" set in
           it wrapped to three lines and took over the card it was a footnote to. */
        var modes = detail.modes.map(function (m) { return m.mode + " " + m.guesses; }).join(" · ");
        var pairs = detail.assets.map(function (a) { return a.symbol + " " + a.guesses; }).join(" · ");
        var breakdown = [modes, pairs].filter(Boolean).join("  ·  ");

        /* The imported browser tally is drawn apart from everything else and never added to it:
           every figure in it is client-supplied, which is why the leaderboard refuses to rank
           on it, and an account that looks too good is exactly when somebody needs to see it. */
        var imported = detail.legacy
            ? "Đã nhập điểm cũ từ trình duyệt: " + detail.legacy.guesses + " lượt, "
              + detail.legacy.correct + " đúng, điểm " + detail.legacy.score
              + " — số do máy người chơi gửi lên, không tính vào bảng xếp hạng."
            : "Không có điểm cũ nhập từ trình duyệt.";

        el.legacy.textContent = (breakdown ? breakdown + ". " : "") + imported;

        el.guesses.innerHTML = "";
        detail.recentGuesses.forEach(function (g) {
            var tr = element("tr");
            cell(tr, clock(g.createdAt), "num");
            cell(tr, g.mode);
            cell(tr, g.symbol);
            var guessed = element("td");
            guessed.appendChild(side(g.guessedDirection));
            tr.appendChild(guessed);
            var actual = element("td");
            actual.appendChild(side(g.actualDirection));
            tr.appendChild(actual);
            /* No third column saying right or wrong: the two badges are the answer, and two of
               the same colour side by side reads faster than a word does. */
            el.guesses.appendChild(tr);
        });

        el.calls.innerHTML = "";
        detail.recentLiveCalls.forEach(function (c) {
            var tr = element("tr");
            cell(tr, clock(c.openTime), "num");
            cell(tr, c.symbol);
            var direction = element("td");
            direction.appendChild(side(c.direction));
            tr.appendChild(direction);
            cell(tr, c.correct === null ? "chưa chốt" : (c.correct ? "Đúng" : "Sai"));
            el.calls.appendChild(tr);
        });
    }

    function closeDetail() {
        el.detail.classList.add("hidden");
    }

    /* ---- the two write actions ---- */

    async function rename(player) {
        var next = window.prompt("Tên hiển thị mới cho " + shortWallet(player.walletAddress), player.displayName);
        if (next === null || next.trim() === player.displayName) return;
        try {
            await api("/" + player.id + "/name", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ displayName: next }),
            });
            await load();
            el.status.textContent = "Đã đổi tên.";
        } catch (e) {
            el.status.textContent = e.message;
        }
    }

    async function remove(player) {
        // Deleting an account takes its guess history with it, which is the part that cannot
        // be recovered — the wallet can always sign in again and start over.
        if (!window.confirm("Xoá tài khoản " + shortWallet(player.walletAddress) + " và toàn bộ "
                + player.guesses + " lượt đoán?\n\nKhông hoàn tác được.")) {
            return;
        }
        try {
            await api("/" + player.id, { method: "DELETE" });
            closeDetail();
            await load();
            el.status.textContent = "Đã xoá tài khoản.";
        } catch (e) {
            el.status.textContent = e.message;
        }
    }

    /* ---- loading ---- */

    async function load() {
        try {
            render(await api("?query=" + encodeURIComponent(query) + "&sort=" + sort
                + "&page=" + page + "&size=" + PAGE_SIZE));
        } catch (e) {
            el.status.textContent = "Không tải được danh sách: " + e.message;
        }
    }

    /** Any change to the filter goes back to the first page: page 4 of a different search is a
        page nobody asked for, and usually an empty one. */
    function refilter() {
        page = 0;
        load();
    }

    el.search.addEventListener("input", function () {
        query = el.search.value.trim();
        window.clearTimeout(searchTimer);
        searchTimer = window.setTimeout(refilter, SEARCH_DEBOUNCE_MS);
    });

    el.sort.addEventListener("click", function (event) {
        var button = event.target.closest(".pill-option");
        if (!button || button.dataset.sort === sort) return;
        Array.prototype.forEach.call(el.sort.children, function (b) { b.classList.remove("active"); });
        button.classList.add("active");
        sort = button.dataset.sort;
        refilter();
    });

    el.prev.addEventListener("click", function () {
        if (page > 0) { page -= 1; load(); }
    });

    el.next.addEventListener("click", function () {
        page += 1;
        load();
    });

    el.detailClose.addEventListener("click", closeDetail);

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load();
    });
})();
