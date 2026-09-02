/**
 * Accounts. Rename and delete; the role column is shown without a control beside it because
 * roles come from candles.admin.wallets and anything set here would be undone on restart.
 */
(function () {
    "use strict";

    var el = {
        section: document.getElementById("admin-players"),
        count: document.getElementById("players-count"),
        rows: document.querySelector("#player-table tbody"),
        status: document.getElementById("admin-status"),
    };

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

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/players" + path, options);
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    function render(players) {
        el.count.textContent = players.length + " tài khoản";
        el.rows.innerHTML = "";
        players.forEach(function (player) {
            var tr = document.createElement("tr");

            var wallet = document.createElement("td");
            wallet.className = "num";
            wallet.textContent = shortWallet(player.walletAddress);
            wallet.title = player.walletAddress;
            tr.appendChild(wallet);

            var name = document.createElement("td");
            name.textContent = player.displayName;
            tr.appendChild(name);

            var role = document.createElement("td");
            var badge = document.createElement("span");
            badge.className = "ops-badge " + (player.role === "ADMIN" ? "is-admin" : "is-off");
            badge.textContent = player.role;
            role.appendChild(badge);
            tr.appendChild(role);

            [player.guesses, player.guesses ? Math.round((player.correct / player.guesses) * 100) + "%" : "—"]
                .forEach(function (v) {
                    var td = document.createElement("td");
                    td.className = "num";
                    td.textContent = v;
                    tr.appendChild(td);
                });

            var last = document.createElement("td");
            last.textContent = when(player.lastPlayedAt);
            tr.appendChild(last);

            var actions = document.createElement("td");
            actions.className = "asset-actions";
            actions.appendChild(action("Đổi tên", function () { rename(player); }));
            if (player.role !== "ADMIN") {
                actions.appendChild(action("Xoá", function () { remove(player); }, "danger-btn"));
            }
            tr.appendChild(actions);

            el.rows.appendChild(tr);
        });
    }

    function action(label, onClick, cls) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = cls || "ghost-btn";
        b.textContent = label;
        b.addEventListener("click", onClick);
        return b;
    }

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
            await load();
            el.status.textContent = "Đã xoá tài khoản.";
        } catch (e) {
            el.status.textContent = e.message;
        }
    }

    async function load() {
        try {
            render(await api("", {}));
        } catch (e) {
            el.status.textContent = "Không tải được danh sách: " + e.message;
        }
    }

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load();
    });
})();
