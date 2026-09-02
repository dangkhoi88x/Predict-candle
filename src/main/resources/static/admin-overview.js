/**
 * The overview pane: is the app all right, answered before the reader has to scroll.
 *
 * Two sources, on purpose. The four headline numbers are lifted from the operations snapshot
 * admin-ops.js has already fetched — it broadcasts `candles:ops`, and asking the server for
 * the same figures a second time would only give a chance to disagree with the pane next
 * door. Everything with a shape to it comes from GET /api/admin/stats, which is the only
 * call here that touches guess history.
 */
(function () {
    "use strict";

    var ROWS = 14;          // cells in the tallest chart column
    var Y_TICKS = 7;
    var SPARK_BARS = 9;

    var el = {
        section: document.getElementById("admin-overview"),
        greeting: document.getElementById("ov-greeting"),
        sub: document.getElementById("ov-sub"),
        refresh: document.getElementById("ov-refresh"),
        newPost: document.getElementById("ov-new-post"),
        alert: document.getElementById("ov-alert"),
        alertText: document.getElementById("ov-alert-text"),
        alertGo: document.getElementById("ov-alert-go"),
        kpis: document.getElementById("ov-kpis"),
        total: document.getElementById("ov-total"),
        range: document.getElementById("ov-range"),
        yaxis: document.getElementById("ov-yaxis"),
        grid: document.getElementById("ov-grid"),
        xaxis: document.getElementById("ov-xaxis"),
        accuracy: document.getElementById("ov-accuracy"),
        accuracySpan: document.getElementById("ov-accuracy-span"),
        accuracyLine: document.getElementById("ov-accuracy-line"),
        accuracyDot: document.getElementById("ov-accuracy-dot"),
        accuracyAxis: document.getElementById("ov-accuracy-axis"),
        accuracyEmpty: document.getElementById("ov-accuracy-empty"),
        playersNow: document.getElementById("ov-players-now"),
        playersSpan: document.getElementById("ov-players-span"),
        playersBars: document.getElementById("ov-players-bars"),
        playersAxis: document.getElementById("ov-players-axis"),
        status: document.getElementById("admin-status"),
    };
    if (!el.section) return;

    var range = "month";
    var stats = null;
    var snapshot = null;

    /* ---- formatting ---- */

    function num(value) {
        return Number(value || 0).toLocaleString("vi-VN");
    }

    function percent(fraction, digits) {
        if (fraction === null || fraction === undefined) return "—";
        return (fraction * 100).toLocaleString("vi-VN", {
            minimumFractionDigits: digits === undefined ? 1 : digits,
            maximumFractionDigits: digits === undefined ? 1 : digits,
        }) + "%";
    }

    /** The ratio against the period before, in the "+0,94" form the design asks for. */
    function delta(value) {
        if (value === null || value === undefined) return null;
        var text = Math.abs(value).toLocaleString("vi-VN", {
            minimumFractionDigits: 2, maximumFractionDigits: 2,
        });
        return (value < 0 ? "−" : "+") + text;
    }

    function clock(iso) {
        if (!iso) return "—";
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " "
            + pad(d.getHours()) + ":" + pad(d.getMinutes());
    }

    var WEEKDAYS = ["CN", "T2", "T3", "T4", "T5", "T6", "T7"];
    var MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                  "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

    function bucketLabel(iso, forRange) {
        var d = new Date(iso);
        if (forRange === "week") return WEEKDAYS[d.getUTCDay()];
        if (forRange === "year") return String(d.getUTCFullYear());
        return MONTHS[d.getUTCMonth()];
    }

    /** Axis ticks want a round ceiling, not the exact maximum. */
    function niceCeiling(max) {
        if (max <= 0) return Y_TICKS - 1;
        var step = Math.pow(10, Math.floor(Math.log10(max / (Y_TICKS - 1))));
        var candidates = [1, 2, 2.5, 5, 10];
        for (var i = 0; i < candidates.length; i++) {
            var size = step * candidates[i];
            if (size * (Y_TICKS - 1) >= max) return size * (Y_TICKS - 1);
        }
        return max;
    }

    function tickLabel(value) {
        if (value >= 1000) {
            return (value / 1000).toLocaleString("vi-VN", { maximumFractionDigits: 1 }) + "k";
        }
        return num(value);
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    /* ---- KPI cards ---- */

    function svgArrow() {
        var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("width", "11");
        svg.setAttribute("height", "11");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("fill", "none");
        svg.setAttribute("stroke", "currentColor");
        svg.setAttribute("stroke-width", "2.4");
        svg.setAttribute("stroke-linecap", "round");
        svg.setAttribute("aria-hidden", "true");
        var path = document.createElementNS("http://www.w3.org/2000/svg", "path");
        path.setAttribute("d", "M12 19V5M6 11l6-6 6 6");
        svg.appendChild(path);
        return svg;
    }

    /**
     * Nine bars, scaled to the tallest. A series that never moves draws flat rather than
     * full: bars that all touch the ceiling would read as a series pinned at its maximum.
     */
    function sparkline(values) {
        var box = element("div", "adm-spark");
        var series = (values || []).slice(-SPARK_BARS);
        var max = Math.max.apply(null, series.concat([0]));
        series.forEach(function (value, i) {
            var bar = element("span", i === series.length - 1 ? "is-now" : null);
            bar.style.height = (max > 0 ? 14 + Math.round((value / max) * 30) : 14) + "px";
            box.appendChild(bar);
        });
        return box;
    }

    function kpiCard(spec) {
        var wrap = element("div", "adm-tray adm-kpi");
        var card = element("div", "adm-card adm-kpi-card");

        var text = element("div", "adm-kpi-text");
        text.appendChild(element("span", "adm-kpi-label", spec.label));
        var figure = element("span", "adm-kpi-figure");
        figure.appendChild(element("b", "adm-kpi-value", spec.value));
        figure.appendChild(element("span", "adm-kpi-unit", spec.unit));
        text.appendChild(figure);
        card.appendChild(text);
        card.appendChild(sparkline(spec.spark));
        wrap.appendChild(card);

        var row = element("div", "adm-kpi-delta");
        var mark = element("span", "adm-delta-mark");
        mark.appendChild(svgArrow());
        row.appendChild(mark);

        var note = element("span", "adm-delta-text");
        var formatted = delta(spec.delta);
        if (formatted === null) {
            // No earlier period to compare against — say so rather than draw a zero.
            note.textContent = "chưa có " + spec.deltaNote + " để so";
        } else {
            note.appendChild(element("b", null, formatted));
            note.appendChild(document.createTextNode(" so với " + spec.deltaNote));
            if (spec.delta < 0) row.classList.add("is-down");
        }
        row.appendChild(note);
        wrap.appendChild(row);
        return wrap;
    }

    function renderKpis() {
        el.kpis.innerHTML = "";
        if (!snapshot) {
            for (var i = 0; i < 4; i++) {
                var placeholder = element("div", "adm-tray adm-kpi");
                var box = element("div", "adm-card adm-kpi-card adm-skeleton");
                box.style.height = "92px";
                placeholder.appendChild(box);
                el.kpis.appendChild(placeholder);
            }
            return;
        }

        var act = snapshot.activity;
        var daily = stats ? stats.daily : [];
        var weekly = stats ? stats.weekly : [];
        var accounts = (stats && stats.accounts) || [];
        var deltas = (stats && stats.deltas) || {};
        var accuracyToday = act.guessesToday ? act.correctToday / act.guessesToday : null;

        /* Every card's sparkline plots the quantity its own big number measures, and `guesses`
           is that quantity here — the ops snapshot's guessesToday counts a timed-out guess,
           so a spark drawn from `answered` would end on a bar that is not the number above. */
        el.kpis.appendChild(kpiCard({
            label: "Lượt đoán hôm nay", value: num(act.guessesToday), unit: "lượt",
            delta: deltas.guessesToday, deltaNote: "hôm qua",
            spark: daily.map(function (b) { return b.guesses; }),
        }));
        el.kpis.appendChild(kpiCard({
            label: "Tỉ lệ đoán đúng",
            value: accuracyToday === null ? "—" : percent(accuracyToday).replace("%", ""),
            unit: "%", delta: deltas.accuracy, deltaNote: "tuần trước",
            spark: weekly.map(function (b) {
                return b.guesses ? b.correct / b.guesses : 0;
            }),
        }));
        el.kpis.appendChild(kpiCard({
            label: "Tài khoản người chơi", value: num(act.players), unit: "ví",
            delta: deltas.players, deltaNote: "tuần trước",
            spark: accounts.map(function (a) { return a.total; }),
        }));
        el.kpis.appendChild(kpiCard({
            label: "Lượt đoán 7 ngày", value: num(act.guessesWeek), unit: "lượt",
            delta: deltas.guessesWeek, deltaNote: "tuần trước",
            spark: rollingSum(daily.map(function (b) { return b.guesses; }), 7),
        }));
    }

    /** Seven-day totals ending at each day, so the 7-day KPI's spark measures what it names. */
    function rollingSum(values, window) {
        var out = [];
        for (var i = window - 1; i < values.length; i++) {
            var total = 0;
            for (var j = i - window + 1; j <= i; j++) total += values[j];
            out.push(total);
        }
        return out;
    }

    /* ---- the guesses chart ---- */

    function renderChart() {
        var buckets = (stats && stats.buckets) || [];
        var totals = buckets.reduce(function (sum, b) { return sum + b.answered; }, 0);
        el.total.textContent = num(totals);

        var max = Math.max.apply(null, buckets.map(function (b) { return b.answered; }).concat([0]));
        var ceiling = niceCeiling(max);

        el.yaxis.innerHTML = "";
        for (var t = 0; t < Y_TICKS; t++) {
            el.yaxis.appendChild(element("span", null, tickLabel(ceiling * t / (Y_TICKS - 1))));
        }

        el.grid.innerHTML = "";
        el.xaxis.innerHTML = "";
        buckets.forEach(function (bucket, index) {
            var filled = ceiling > 0 ? Math.round((bucket.answered / ceiling) * ROWS) : 0;
            // A bucket with any activity at all keeps one cell: rounding it away would draw
            // "nothing happened" over a day when something did.
            if (filled === 0 && bucket.answered > 0) filled = 1;
            var longCells = bucket.answered
                ? Math.round((bucket.longCount / bucket.answered) * filled)
                : 0;

            var column = element("div", "adm-grid-col");
            var stack = element("div", "adm-grid-stack");
            for (var row = 0; row < ROWS; row++) {
                var cls = row >= filled ? null : (row < longCells ? "is-long" : "is-short");
                stack.appendChild(element("span", cls));
            }
            column.appendChild(stack);
            column.title = bucketLabel(bucket.start, range) + " · LONG " + num(bucket.longCount)
                + " · SHORT " + num(bucket.shortCount);
            el.grid.appendChild(column);

            var label = element("span", index === buckets.length - 1 ? "is-focus" : null,
                bucketLabel(bucket.start, range));
            el.xaxis.appendChild(label);
        });
    }

    /* ---- the two small panels ---- */

    function renderAccuracy() {
        var weekly = (stats && stats.weekly) || [];
        var totals = (stats && stats.totals) || {};
        el.accuracy.textContent = percent(totals.accuracy);
        el.accuracySpan.textContent = weekly.length + " tuần";
        // "toàn hệ thống" is across players, not across all time — the window is the kicker's.


        // Buckets nobody played are left out rather than plotted at 0%: a quiet week is not
        // a week when every guess was wrong.
        var points = weekly
            .map(function (b, i) {
                return b.guesses ? { i: i, value: b.correct / b.guesses } : null;
            })
            .filter(Boolean);

        /* One point cannot make a line, and drawing the axis with nothing on it looks like a
           failure rather than a young deployment. Swap the whole plot for a sentence. */
        var plottable = points.length >= 2;
        el.accuracyEmpty.classList.toggle("hidden", plottable);
        el.accuracyLine.closest("svg").classList.toggle("hidden", !plottable);
        el.accuracyAxis.classList.toggle("hidden", !plottable);

        if (!plottable) {
            el.accuracyLine.setAttribute("points", "");
            el.accuracyDot.classList.add("hidden");
        } else {
            /* The scale is pinned so 50% lands on y=48, where the dashed rule is drawn. An
               axis fitted to the data instead would put that rule wherever it happened to
               fall, and the one thing this panel is for is "are we above the coin flip". */
            var spread = Math.max.apply(null, points.map(function (p) {
                return Math.abs(p.value - 0.5);
            }).concat([0.02])) * 1.25;
            var scale = function (value) { return 48 - ((value - 0.5) / spread) * 48; };
            var span = Math.max(weekly.length - 1, 1);
            var coords = points.map(function (p) {
                return Math.round((p.i / span) * 320) + "," + scale(p.value).toFixed(1);
            });
            el.accuracyLine.setAttribute("points", coords.join(" "));
            var last = points[points.length - 1];
            el.accuracyDot.setAttribute("cx", String(Math.round((last.i / span) * 320)));
            el.accuracyDot.setAttribute("cy", scale(last.value).toFixed(1));
            el.accuracyDot.classList.remove("hidden");
        }

        el.accuracyAxis.innerHTML = "";
        if (plottable && weekly.length) {
            [0, Math.floor(weekly.length / 2), weekly.length - 1].forEach(function (i) {
                var d = new Date(weekly[i].start);
                el.accuracyAxis.appendChild(element("span", null,
                    d.getUTCDate() + "/" + (d.getUTCMonth() + 1)));
            });
        }
    }

    function renderPlayers() {
        var daily = (stats && stats.daily) || [];
        var totals = (stats && stats.totals) || {};
        el.playersNow.textContent = num(totals.activePlayersToday);
        el.playersSpan.textContent = daily.length + " ngày";

        var max = Math.max.apply(null, daily.map(function (b) { return b.activePlayers; }).concat([0]));
        el.playersBars.innerHTML = "";
        daily.forEach(function (bucket, i) {
            var bar = element("span", i === daily.length - 1 ? "is-now" : null);
            bar.style.height = (max > 0 ? Math.max(2, Math.round((bucket.activePlayers / max) * 96)) : 2) + "px";
            bar.title = num(bucket.activePlayers) + " ví";
            el.playersBars.appendChild(bar);
        });

        el.playersAxis.innerHTML = "";
        el.playersAxis.appendChild(element("span", null, daily.length + " ngày trước"));
        el.playersAxis.appendChild(element("span", null, "hôm nay"));
    }

    /* ---- the header line and the stale-feed strip ---- */

    function renderHeader() {
        var user = window.CandleAuth && window.CandleAuth.getUser();
        el.greeting.textContent = user && user.displayName
            ? "Chào lại, " + user.displayName
            : "Tổng quan";

        if (!snapshot) {
            el.sub.textContent = "Đang đọc trạng thái…";
            return;
        }
        var stale = snapshot.assets.filter(function (a) { return a.stale; });
        el.sub.textContent = "Cập nhật " + clock(snapshot.generatedAt) + " · ";
        if (stale.length) {
            el.sub.appendChild(element("span", "is-stale", stale.length + " cặp đang trễ"));
        } else {
            el.sub.appendChild(document.createTextNode(
                "dữ liệu nến đang theo kịp trên " + snapshot.assets.length + " cặp"));
        }

        el.alert.classList.toggle("hidden", !stale.length);
        if (stale.length) {
            el.alertText.textContent = stale.map(function (a) { return a.symbol; }).join(", ")
                + " đang trễ — nến mới nhất chưa về.";
        }
    }

    /* ---- loading ---- */

    async function loadStats(fresh) {
        try {
            var res = await window.CandleAuth.authFetch("/api/admin/stats?range=" + range
                + (fresh ? "&fresh=true" : ""));
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            stats = payload;
            renderKpis();
            renderChart();
            renderAccuracy();
            renderPlayers();
        } catch (e) {
            el.status.textContent = "Không đọc được thống kê: " + e.message;
        }
    }

    /* ---- wiring ---- */

    el.range.addEventListener("click", function (event) {
        var option = event.target.closest(".pill-option");
        if (!option || option.classList.contains("active")) return;
        Array.prototype.forEach.call(el.range.querySelectorAll(".pill-option"), function (b) {
            b.classList.toggle("active", b === option);
        });
        range = option.dataset.range;
        loadStats();
    });

    el.refresh.addEventListener("click", function () {
        // One button, both sources: the ops panel owns the snapshot and re-broadcasts it.
        var opsRefresh = document.getElementById("ops-refresh");
        if (opsRefresh) opsRefresh.click();
        loadStats(true);
    });

    el.newPost.addEventListener("click", function () {
        if (window.CandleAdminNav) window.CandleAdminNav.go("blog");
        var newBtn = document.getElementById("blog-new");
        if (newBtn) newBtn.click();
    });

    el.alertGo.addEventListener("click", function () {
        if (window.CandleAdminNav) window.CandleAdminNav.go("ops");
    });

    document.addEventListener("candles:ops", function (event) {
        snapshot = event.detail.snapshot;
        renderHeader();
        renderKpis();
    });

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) {
            renderHeader();
            renderKpis();
            loadStats();
        }
    });
})();
