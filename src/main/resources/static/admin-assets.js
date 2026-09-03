/**
 * Trading pairs: what is on the menu, and how a new one gets there.
 *
 * The order matters and the interface enforces it — add, backfill, then enable. A pair with no
 * candles cannot produce a round, so offering it in the picker would hand players a chart
 * request the server cannot answer.
 */
(function () {
    "use strict";

    var el = {
        section: document.getElementById("admin-assets"),
        form: document.getElementById("asset-add"),
        symbol: document.getElementById("asset-symbol"),
        name: document.getElementById("asset-name"),
        rows: document.querySelector("#asset-table tbody"),
        status: document.getElementById("admin-status"),
    };

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/assets" + path, options);
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    function button(label, onClick, cls) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = cls || "ghost-btn";
        b.textContent = label;
        b.addEventListener("click", function () { onClick(b); });
        return b;
    }

    function render(assets) {
        el.rows.innerHTML = "";
        assets.forEach(function (asset) {
            var tr = document.createElement("tr");
            [asset.symbol, asset.name, asset.candles.toLocaleString("vi-VN")].forEach(function (v, i) {
                var td = document.createElement("td");
                if (i === 2) td.className = "num";
                td.textContent = v;
                tr.appendChild(td);
            });

            var state = document.createElement("td");
            var badge = document.createElement("span");
            badge.className = "ops-badge " + (asset.enabled ? "is-good" : "is-off");
            badge.textContent = asset.enabled ? "Đang bật" : "Đang tắt";
            state.appendChild(badge);
            tr.appendChild(state);

            var actions = document.createElement("td");
            actions.className = "asset-actions";
            actions.appendChild(button("↑", function (b) {
                run(b, "/" + asset.id + "/move", { direction: "up" });
            }));
            actions.appendChild(button("↓", function (b) {
                run(b, "/" + asset.id + "/move", { direction: "down" });
            }));
            if (!asset.candles) {
                actions.appendChild(button("Backfill", function (b) { run(b, "/" + asset.id + "/backfill", {}); }));
            }
            actions.appendChild(button(asset.enabled ? "Tắt" : "Bật", function (b) {
                run(b, "/" + asset.id + "/enabled", { enabled: !asset.enabled });
            }));
            tr.appendChild(actions);

            el.rows.appendChild(tr);
        });
    }

    async function run(button, path, body) {
        button.disabled = true;
        var label = button.textContent;
        button.textContent = "…";
        el.status.textContent = "Đang xử lý…";
        try {
            await api(path, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            await load();
            el.status.textContent = "Xong.";
        } catch (e) {
            el.status.textContent = e.message;
        } finally {
            button.disabled = false;
            button.textContent = label;
        }
    }

    async function load() {
        try {
            render(await api("", {}));
        } catch (e) {
            el.status.textContent = "Không tải được danh sách cặp: " + e.message;
        }
    }

    el.form.addEventListener("submit", async function (event) {
        event.preventDefault();
        var symbol = el.symbol.value.trim();
        if (!symbol) return;
        el.status.textContent = "Đang thêm " + symbol.toUpperCase() + "…";
        try {
            await api("", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ symbol: symbol, name: el.name.value.trim() }),
            });
            el.symbol.value = "";
            el.name.value = "";
            await load();
            el.status.textContent = "Đã thêm. Chạy Backfill để tải lịch sử trước khi bật.";
        } catch (e) {
            el.status.textContent = e.message;
        }
    });

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load();
    });
})();
