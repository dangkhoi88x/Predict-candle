/**
 * Paper trading, from outside a player's account.
 *
 * Nothing here computes a balance. Every figure comes off /api/admin/demo, which folds the
 * trade log through the same DemoPortfolio the player's own terminal uses — a client doing its
 * own arithmetic would eventually disagree with the server about how much money somebody has,
 * which is the rule the trade tab already follows on the game side.
 *
 * One action, and it is the player's own reset: it moves the mark the fold reads from and
 * deletes nothing. There is deliberately no way to credit an account or edit a fill.
 */
(function () {
    "use strict";

    var PAGE_SIZE = 25;

    var el = {
        section: document.getElementById("admin-demo"),
        count: document.getElementById("demo-count"),
        refresh: document.getElementById("demo-refresh"),
        figures: document.getElementById("demo-figures"),
        foot: document.getElementById("demo-foot"),
        rows: document.querySelector("#demo-table tbody"),
        prev: document.getElementById("demo-prev"),
        next: document.getElementById("demo-next"),
        range: document.getElementById("demo-range"),
        detail: document.getElementById("demo-detail"),
        detailTitle: document.getElementById("demo-detail-title"),
        detailSub: document.getElementById("demo-detail-sub"),
        detailNote: document.getElementById("demo-detail-note"),
        detailReset: document.getElementById("demo-detail-reset"),
        detailClose: document.getElementById("demo-detail-close"),
        positions: document.querySelector("#demo-positions tbody"),
        fills: document.querySelector("#demo-fills tbody"),
        status: document.getElementById("admin-status"),
    };
    if (!el.section) return;

    var page = 0;
    var open = null;   // the account whose detail is on screen, so a reset can redraw it

    /* ---- formatting ---- */

    function shortWallet(address) {
        return address.slice(0, 6) + "…" + address.slice(-4);
    }

    /**
     * Money always carries its cents, unlike the price columns elsewhere on this page: these are
     * balances somebody is comparing against each other, and a figure that drops its decimals on
     * one row and keeps them on the next cannot be scanned down a column.
     *
     * Null is an en dash, never zero. An account holding something the feed cannot price has an
     * unknown value, and drawing that as $0 would say the position is worthless.
     */
    function usd(value) {
        if (value === null || value === undefined) return "—";
        return "$" + Number(value).toLocaleString("vi-VN",
            { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    /** Signed, because a P&L that does not say which way it went is half a number. */
    function signedUsd(value) {
        if (value === null || value === undefined) return "—";
        var n = Number(value);
        return (n > 0 ? "+" : n < 0 ? "−" : "") + usd(Math.abs(n));
    }

    function qty(value) {
        if (value === null || value === undefined) return "—";
        return Number(value).toLocaleString("vi-VN", { maximumFractionDigits: 8 });
    }

    function price(value) {
        if (value === null || value === undefined) return "—";
        var n = Number(value);
        return n.toLocaleString("vi-VN", { maximumFractionDigits: n >= 100 ? 2 : 6 });
    }

    function count(value) {
        return Number(value || 0).toLocaleString("vi-VN");
    }

    function clock(iso) {
        if (!iso) return "chưa giao dịch";
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
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

    /** Green above zero, red below, plain at zero — the up/down pair the whole app is read by. */
    function pnlCell(row, value) {
        var td = element("td", "num", signedUsd(value));
        if (value !== null && value !== undefined && Number(value) !== 0) {
            td.classList.add(Number(value) > 0 ? "is-up" : "is-down");
        }
        row.appendChild(td);
    }

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/demo" + path, options);
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    /* ---- the header ---- */

    function figure(value, note, base) {
        var box = element("div", "adm-figure");
        box.appendChild(element("b", "adm-mini-value", value));
        box.appendChild(element("span", "adm-mini-note", note));
        box.appendChild(element("span", "adm-figure-base", base));
        return box;
    }

    function renderSummary(s) {
        el.figures.innerHTML = "";
        /* Two counts, not one. Opening the terminal creates the account row, so the gap between
           these is how many people looked at paper trading and never placed a trade. */
        el.figures.appendChild(figure(count(s.accounts), "tài khoản đã mở",
            count(s.tradingAccounts) + " đã từng đặt lệnh"));
        el.figures.appendChild(figure(count(s.trades), "lệnh đã khớp",
            count(s.tradesToday) + " hôm nay · " + count(s.tradesWeek) + " trong 7 ngày"));
        el.figures.appendChild(figure(usd(s.fees), "phí đã thu",
            count(s.resets) + " lần đặt lại"));

        el.foot.textContent = "Vốn khởi điểm " + usd(s.startingBalance) + " mỗi tài khoản, phí "
            + s.feeBps + " bps mỗi chiều — đọc từ cấu hình đang chạy, sửa trong application.yaml"
            + " rồi khởi động lại. Không có cột số dư ở đâu cả: mọi con số ở đây gấp lại từ nhật ký lệnh.";
    }

    /* ---- the list ---- */

    function render(data) {
        renderSummary(data.summary);

        var from = data.total === 0 ? 0 : data.page * data.size + 1;
        var to = data.page * data.size + data.accounts.length;
        el.count.textContent = data.total + " tài khoản demo";
        el.range.textContent = data.total === 0 ? "chưa có tài khoản nào" : from + "–" + to + " / " + data.total;
        el.prev.disabled = data.page === 0;
        el.next.disabled = !data.hasMore;

        el.rows.innerHTML = "";
        if (!data.accounts.length) {
            var empty = element("tr");
            var td = element("td", null, "Chưa ai mở sàn demo.");
            td.colSpan = 8;
            empty.appendChild(td);
            el.rows.appendChild(empty);
            return;
        }

        data.accounts.forEach(function (account) {
            var tr = element("tr");

            var wallet = element("td", "num", shortWallet(account.walletAddress));
            wallet.title = account.walletAddress;
            tr.appendChild(wallet);

            cell(tr, account.displayName);
            cell(tr, count(account.trades), "num");
            cell(tr, usd(account.cash), "num");
            /* Null when the feed could not price a holding, and the en dash says so rather than
               reporting an equity that quietly left one out. There is no separate "positions"
               column: the gap between this and cash is what is held, and how many pairs that is
               spread over is a question the detail view answers. */
            var equity = element("td", "num", usd(account.equity));
            if (account.positions) {
                equity.title = account.positions + " vị thế đang mở";
            }
            tr.appendChild(equity);
            pnlCell(tr, account.realisedPnl);
            cell(tr, clock(account.lastTradeAt));

            var actions = element("td", "asset-actions");
            actions.appendChild(action("Chi tiết", function () { openDetail(account.userId); }));
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

    async function openDetail(userId) {
        try {
            open = userId;
            renderDetail(await api("/" + userId));
        } catch (e) {
            open = null;
            el.status.textContent = "Không đọc được tài khoản demo: " + e.message;
        }
    }

    function renderDetail(account) {
        el.detail.classList.remove("hidden");
        el.detailTitle.textContent = account.displayName;
        el.detailSub.textContent = shortWallet(account.walletAddress)
            + " · tiền mặt " + usd(account.cash)
            + " · tài sản " + usd(account.equity)
            + " · đã chốt " + signedUsd(account.realisedPnl)
            + " · đang mở " + signedUsd(account.unrealisedPnl);

        /* The one number nobody inside the game can see. A reset moves opened_at and deletes
           nothing, so an account can hold rows it no longer shows — which reads as data loss
           from out here unless the page says what it is. */
        el.detailNote.textContent = "Mở sàn lúc " + clock(account.openedAt)
            + " · đặt lại " + account.resets + " lần"
            + (account.tradesBeforeReset
                ? " · còn " + count(account.tradesBeforeReset)
                  + " lệnh trước lần đặt lại gần nhất, vẫn nằm trong cơ sở dữ liệu nhưng không được tính."
                : " · không có lệnh nào nằm trước mốc đặt lại.");

        el.positions.innerHTML = "";
        if (!account.positions.length) {
            var none = element("tr");
            var td = element("td", null, "Không giữ vị thế nào.");
            td.colSpan = 5;
            none.appendChild(td);
            el.positions.appendChild(none);
        }
        account.positions.forEach(function (p) {
            var tr = element("tr");
            cell(tr, p.symbol);
            cell(tr, qty(p.quantity), "num");
            cell(tr, price(p.averageCost), "num");
            cell(tr, price(p.price), "num");
            pnlCell(tr, p.unrealisedPnl);
            el.positions.appendChild(tr);
        });

        el.fills.innerHTML = "";
        account.trades.forEach(function (f) {
            var tr = element("tr");
            cell(tr, clock(f.at), "num");
            cell(tr, f.symbol);
            var side = element("td");
            side.appendChild(element("span",
                "ops-badge " + (f.side === "BUY" ? "is-long" : "is-short"),
                f.side === "BUY" ? "MUA" : "BÁN"));
            tr.appendChild(side);
            cell(tr, qty(f.quantity), "num");
            cell(tr, price(f.price), "num");
            cell(tr, price(f.fee), "num");
            el.fills.appendChild(tr);
        });
    }

    function closeDetail() {
        open = null;
        el.detail.classList.add("hidden");
    }

    /**
     * The warning says what a reset does and what it does not. It is the player's own button, so
     * nothing is deleted — but the position they were holding is gone, and they did not ask for
     * it, so this is worth spelling out rather than confirming with a shrug.
     */
    async function reset() {
        if (open === null) return;
        if (!window.confirm("Đặt lại tài khoản demo này?\n\nVị thế đang mở và lãi/lỗ sẽ về mốc"
                + " ban đầu. Lệnh cũ không bị xoá — chúng vẫn nằm trong cơ sở dữ liệu, chỉ không"
                + " còn được tính vào số dư.")) {
            return;
        }
        try {
            renderDetail(await api("/" + open + "/reset", { method: "POST" }));
            await load();
            el.status.textContent = "Đã đặt lại tài khoản demo.";
        } catch (e) {
            el.status.textContent = e.message;
        }
    }

    /* ---- loading ---- */

    async function load() {
        try {
            render(await api("?page=" + page + "&size=" + PAGE_SIZE));
        } catch (e) {
            el.status.textContent = "Không tải được sàn demo: " + e.message;
        }
    }

    el.refresh.addEventListener("click", function () { load(); });
    el.detailClose.addEventListener("click", closeDetail);
    el.detailReset.addEventListener("click", reset);

    el.prev.addEventListener("click", function () {
        if (page > 0) { page -= 1; load(); }
    });

    el.next.addEventListener("click", function () {
        page += 1;
        load();
    });

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load();
    });
})();
