/**
 * The operations panel: is the candle feed current, is the schema where it should be, what is
 * the game actually configured to do, and how much play happened today.
 *
 * Read-only apart from one button. Sync now runs the same delta fetch the hourly job runs —
 * useful when a chart looks behind and the question is whether the ingest is broken or just
 * has not run yet.
 */
(function () {
    "use strict";

    var el = {
        section: document.getElementById("admin-ops"),
        refresh: document.getElementById("ops-refresh"),
        generated: document.getElementById("ops-generated"),
        cards: document.getElementById("ops-cards"),
        assets: document.querySelector("#ops-assets tbody"),
        settings: document.getElementById("ops-settings"),
        status: document.getElementById("admin-status"),
        navBadge: document.getElementById("nav-stale-count"),
    };

    function clock(iso) {
        if (!iso) return "—";
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    function lag(minutes) {
        if (minutes === null || minutes === undefined) return "—";
        if (minutes < 90) return minutes + " phút";
        if (minutes < 2880) return Math.round(minutes / 60) + " giờ";
        return Math.round(minutes / 1440) + " ngày";
    }

    function card(label, value, hint, tone) {
        var box = document.createElement("div");
        box.className = "ops-card" + (tone ? " tone-" + tone : "");
        box.innerHTML = '<span class="ops-card-label"></span><strong class="ops-card-value"></strong>'
            + '<span class="ops-card-hint"></span>';
        box.querySelector(".ops-card-label").textContent = label;
        box.querySelector(".ops-card-value").textContent = value;
        box.querySelector(".ops-card-hint").textContent = hint || "";
        return box;
    }

    function render(snapshot) {
        el.generated.textContent = "Cập nhật " + clock(snapshot.generatedAt);

        var stale = snapshot.assets.filter(function (a) { return a.stale; }).length;
        var act = snapshot.activity;

        /* A late feed is the one thing on this page worth seeing from another pane, so the
           count rides on the nav item. Zero draws nothing — a badge reading 0 is noise. */
        if (el.navBadge) {
            el.navBadge.textContent = stale || "";
            el.navBadge.classList.toggle("hidden", !stale);
        }
        /* The overview pane wants these same figures. Announcing the snapshot it already
           fetched is cheaper, and truthful in a way a second call a second later is not. */
        document.dispatchEvent(new CustomEvent("candles:ops", { detail: { snapshot: snapshot } }));

        var accuracy = act.guessesToday
            ? Math.round((act.correctToday / act.guessesToday) * 100) + "%"
            : "—";
        // "correctToday" only has a verdict for calls whose candle has already closed, so
        // accuracy is read against that settled count, not against every call placed today —
        // a call still in flight is not a wrong guess, just one with no answer yet.
        var liveAccuracy = act.liveSettledToday
            ? Math.round((act.liveCorrectToday / act.liveSettledToday) * 100) + "%"
            : "—";

        el.cards.innerHTML = "";
        el.cards.appendChild(card("Dữ liệu nến",
            stale ? stale + " asset trễ" : "Đang theo kịp",
            snapshot.assets.length + " asset", stale ? "bad" : "good"));
        el.cards.appendChild(card("Schema", "v" + snapshot.schema.currentVersion,
            snapshot.schema.appliedMigrations + " đã chạy · "
            + snapshot.schema.pendingMigrations + " chờ",
            snapshot.schema.pendingMigrations ? "bad" : "good"));
        el.cards.appendChild(card("Lượt đoán hôm nay", act.guessesToday.toLocaleString("vi-VN"),
            "đúng " + accuracy + " · 7 ngày: " + act.guessesWeek.toLocaleString("vi-VN")));
        el.cards.appendChild(card("Vòng trực tiếp hôm nay", act.liveCallsToday.toLocaleString("vi-VN"),
            "đúng " + liveAccuracy + " (" + act.liveSettledToday + "/" + act.liveCallsToday + " đã chốt)"
            + " · 7 ngày: " + act.liveCallsWeek.toLocaleString("vi-VN")));
        el.cards.appendChild(card("Tài khoản", act.players.toLocaleString("vi-VN"),
            act.admins + " admin"));
        el.cards.appendChild(card("Nội dung", act.contentItems + " mục",
            act.publishedBlogPosts + "/" + act.blogPosts + " bài blog đã đăng"));

        el.assets.innerHTML = "";
        snapshot.assets.forEach(function (asset) {
            var tr = document.createElement("tr");
            [asset.symbol, asset.timeframe, asset.candles.toLocaleString("vi-VN"),
             clock(asset.latestCandle), lag(asset.lagMinutes)].forEach(function (value, i) {
                var td = document.createElement("td");
                if (i === 2 || i === 4) td.className = "num";
                td.textContent = value;
                tr.appendChild(td);
            });

            var state = document.createElement("td");
            var badge = document.createElement("span");
            badge.className = "ops-badge " + (asset.stale ? "is-bad" : "is-good");
            badge.textContent = asset.stale ? "Trễ" : "Ổn";
            state.appendChild(badge);
            tr.appendChild(state);

            var action = document.createElement("td");
            var sync = document.createElement("button");
            sync.type = "button";
            sync.className = "ghost-btn";
            sync.textContent = "Sync";
            sync.addEventListener("click", function () { syncNow(asset.symbol, sync); });
            action.appendChild(sync);
            tr.appendChild(action);

            el.assets.appendChild(tr);
        });

        var s = snapshot.settings;
        el.settings.innerHTML = "";
        [["Khung thời gian", s.timeframe], ["Nến hiển thị", s.visibleCandles],
         ["Lượt đoán / chart", s.guessesPerChart], ["Nến reveal", s.revealCandles],
         ["Nến bối cảnh", s.contextPadding], ["Giây mỗi lượt", s.guessSeconds],
         ["Round / phút", s.roundsPerMinute], ["Lượt đoán / phút", s.guessesPerMinute]
        ].forEach(function (pair) {
            var row = document.createElement("div");
            row.className = "ops-setting";
            var k = document.createElement("span");
            k.textContent = pair[0];
            var v = document.createElement("b");
            v.textContent = pair[1];
            row.appendChild(k);
            row.appendChild(v);
            el.settings.appendChild(row);
        });
    }

    async function load() {
        el.status.textContent = "Đang đọc trạng thái…";
        try {
            var res = await window.CandleAuth.authFetch("/api/admin/ops");
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            render(payload);
            el.status.textContent = "";
        } catch (e) {
            el.status.textContent = "Không đọc được trạng thái: " + e.message;
        }
    }

    async function syncNow(symbol, button) {
        button.disabled = true;
        var label = button.textContent;
        button.textContent = "Đang sync…";
        el.status.textContent = "Đang đồng bộ " + symbol + "…";
        try {
            var res = await window.CandleAuth.authFetch("/api/admin/ops/sync/" + encodeURIComponent(symbol),
                { method: "POST" });
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            el.status.textContent = symbol + ": " + payload.candles.toLocaleString("vi-VN")
                + " nến, mới nhất " + clock(payload.latestCandle) + ".";
            await load();
        } catch (e) {
            el.status.textContent = "Sync thất bại: " + e.message;
        } finally {
            button.disabled = false;
            button.textContent = label;
        }
    }

    el.refresh.addEventListener("click", load);

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load();
    });
})();
