/**
 * Live rounds: the ones people actually called, and the one lever there is over them.
 *
 * The live game stores no rounds — a round is a candle, named by the clock — so a result is
 * recomputed from the exchange's price on every read. That is what makes a bad candle
 * unfixable from inside the game and why the only action here is a delete: there is no stored
 * verdict to correct, only calls that can stop existing.
 */
(function () {
    "use strict";

    var el = {
        section: document.getElementById("admin-live"),
        pill: document.getElementById("live-asset-pill"),
        refresh: document.getElementById("live-refresh"),
        note: document.getElementById("live-note"),
        rows: document.querySelector("#live-table tbody"),
        detail: document.getElementById("live-detail"),
        detailTitle: document.getElementById("live-detail-title"),
        detailSub: document.getElementById("live-detail-sub"),
        detailClose: document.getElementById("live-detail-close"),
        calls: document.querySelector("#live-calls tbody"),
        status: document.getElementById("admin-status"),
    };
    if (!el.section) return;

    var asset = null;
    var openRound = null;   // the round whose roster is on screen, so a refresh can redraw it

    /* ---- formatting ---- */

    function shortWallet(address) {
        return address.slice(0, 6) + "…" + address.slice(-4);
    }

    function clock(iso) {
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    /** Prices carry four to eight significant decimals depending on the pair; this is the
        column being scanned rather than a figure anyone is about to act on. */
    function price(value) {
        if (value === null || value === undefined) return "—";
        var n = Number(value);
        return n.toLocaleString("vi-VN", { maximumFractionDigits: n >= 100 ? 2 : 6 });
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

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/live" + path, options);
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    /* ---- the asset picker ---- */

    /**
     * Built from the admin asset list rather than from the game's enabled pairs: a pair
     * switched off still has every round anybody ever played on it, and turning it off is
     * often exactly why somebody came looking.
     */
    async function buildPicker() {
        var assets = await window.CandleAuth.authFetch("/api/admin/assets").then(function (r) { return r.json(); });
        el.pill.innerHTML = "";
        assets.forEach(function (a, index) {
            var button = element("button", "pill-option" + (index === 0 ? " active" : ""), a.shortSymbol || a.symbol);
            button.type = "button";
            button.title = a.name || a.symbol;
            button.addEventListener("click", function () {
                Array.prototype.forEach.call(el.pill.children, function (b) { b.classList.remove("active"); });
                button.classList.add("active");
                asset = a.symbol;
                closeDetail();
                load();
            });
            el.pill.appendChild(button);
        });
        asset = assets.length ? assets[0].symbol : null;
    }

    /* ---- the round list ---- */

    function render(data) {
        el.rows.innerHTML = "";
        if (!data.rounds.length) {
            var empty = element("tr");
            var td = element("td", null, "Chưa có ai dự đoán vòng nào của cặp này.");
            td.colSpan = 8;
            empty.appendChild(td);
            el.rows.appendChild(empty);
            return;
        }

        data.rounds.forEach(function (round) {
            var tr = element("tr");
            cell(tr, "#" + round.number, "num");
            cell(tr, clock(round.openTime));

            var result = element("td");
            if (round.settled) {
                result.appendChild(element("span",
                    "ops-badge " + (round.result === "LONG" ? "is-long" : "is-short"), round.result));
            } else {
                /* Unsettled and already closed is the row worth chasing: the round is over, the
                   calls are recorded, and no candle ever arrived to decide them. Those calls
                   score nothing anywhere until the gap in candle history is filled. */
                var closed = new Date(round.closeAt).getTime() <= Date.now();
                result.appendChild(element("span", "ops-badge " + (closed ? "is-stale" : "is-off"),
                    closed ? "thiếu nến" : "đang chạy"));
            }
            tr.appendChild(result);

            cell(tr, round.settled ? price(round.open) + " → " + price(round.close) : "—", "num");
            cell(tr, round.longCount, "num");
            cell(tr, round.shortCount, "num");
            cell(tr, round.settled ? round.correct + "/" + round.calls : "—", "num");

            var actions = element("td", "asset-actions");
            actions.appendChild(action("Xem", function () { openDetail(round); }));
            actions.appendChild(action("Gỡ vòng", function () { voidRound(round); }, "danger-btn"));
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

    /* ---- one round's roster ---- */

    async function openDetail(round) {
        openRound = round.number;
        try {
            renderDetail(await api("/rounds/" + round.number + "?asset=" + encodeURIComponent(asset)));
        } catch (e) {
            openRound = null;
            el.status.textContent = "Không đọc được vòng #" + round.number + ": " + e.message;
        }
    }

    function renderDetail(detail) {
        el.detail.classList.remove("hidden");
        el.detailTitle.textContent = "Vòng #" + detail.number + " · " + detail.asset;
        el.detailSub.textContent = detail.settled
            ? clock(detail.openTime) + " · " + price(detail.open) + " → " + price(detail.close)
              + " · " + detail.result
            : clock(detail.openTime) + " · chưa có nến để chốt";

        el.calls.innerHTML = "";
        detail.calls.forEach(function (call) {
            var tr = element("tr");

            var wallet = element("td", "num", shortWallet(call.walletAddress));
            wallet.title = call.walletAddress;
            tr.appendChild(wallet);

            cell(tr, call.displayName);

            var side = element("td");
            side.appendChild(element("span",
                "ops-badge " + (call.direction === "LONG" ? "is-long" : "is-short"), call.direction));
            tr.appendChild(side);

            cell(tr, clock(call.createdAt), "num");
            // null is the honest answer while the round has no candle: neither right nor wrong.
            cell(tr, call.correct === null ? "—" : (call.correct ? "Đúng" : "Sai"));

            el.calls.appendChild(tr);
        });
    }

    function closeDetail() {
        openRound = null;
        el.detail.classList.add("hidden");
        el.calls.innerHTML = "";
    }

    /* ---- voiding ---- */

    /**
     * The warning names both consequences because neither is obvious from the button. The calls
     * are deleted rather than marked, so nothing records that the round was ever played — and a
     * player whose only play that day was this round loses that day off their streak with it.
     */
    async function voidRound(round) {
        if (!window.confirm("Gỡ vòng #" + round.number + " (" + asset + ")?\n\n"
                + round.calls + " lượt dự đoán sẽ bị xoá hẳn: điểm, bảng xếp hạng và chuỗi ngày"
                + " của những người đã gọi vòng này đều tính lại như thể vòng chưa từng xảy ra."
                + "\n\nKhông hoàn tác được.")) {
            return;
        }
        try {
            var result = await api("/rounds/" + round.number + "?asset=" + encodeURIComponent(asset),
                { method: "DELETE" });
            if (openRound === round.number) closeDetail();
            await load();
            el.status.textContent = "Đã gỡ vòng #" + round.number + " — xoá " + result.removed + " lượt dự đoán.";
        } catch (e) {
            el.status.textContent = e.message;
        }
    }

    /* ---- loading ---- */

    async function load() {
        if (!asset) return;
        try {
            render(await api("/rounds?asset=" + encodeURIComponent(asset)));
        } catch (e) {
            el.status.textContent = "Không tải được danh sách vòng: " + e.message;
        }
    }

    el.refresh.addEventListener("click", function () { load(); });
    el.detailClose.addEventListener("click", closeDetail);

    document.addEventListener("candles:admin", async function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (!event.detail.admin) return;
        try {
            await buildPicker();
        } catch (e) {
            el.note.textContent = "Không đọc được danh sách cặp: " + e.message;
            return;
        }
        load();
    });
})();
