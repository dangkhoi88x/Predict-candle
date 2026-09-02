/**
 * "Mẫu Hình Kỹ Thuật" tab — classic multi-candle chart patterns (Head & Shoulders, Double
 * Top/Bottom, Triangles, Flags, Wedges, Cup & Handle…), as opposed to the 1-3 candle Japanese
 * candlestick patterns already covered in the "Mẫu Nến" tab. Each pattern is illustrated by
 * generating a longer synthetic OHLC swing path from a handful of hand-picked price control
 * points (peaks/troughs), since hand-writing 30+ individual candles per pattern isn't practical.
 *
 * "Tìm ví dụ thật" calls /api/technical-patterns/{id}/example, backed by a swing-point
 * (ZigZag) scan of real history server-side — see TechnicalPatternLibrary.java.
 */
(function () {
    "use strict";

    var UP = "var(--up)";
    var DOWN = "var(--down)";
    var ACCENT = "var(--accent)";
    var GRID = "var(--muted)";
    var HOVER_BADGE_BG = "var(--text)";
    var HOVER_BADGE_TEXT = "var(--panel)";
    var SVG_NS = "http://www.w3.org/2000/svg";

    var selectedAsset = "BTCUSDT";

    var TAG_LABEL = {
        bullish: "Tăng",
        bearish: "Giảm",
        reversal: "Đảo chiều",
        continuation: "Tiếp diễn",
    };

    function round2(v) { return Math.round(v * 100) / 100; }

    // Turns a list of price control points (swing highs/lows) into a candle sequence by
    // interpolating `perLeg` candles between each consecutive pair, with a little wick noise
    // so it still reads as a real chart rather than a straight ruled line.
    function buildSwingCandles(points, perLeg) {
        perLeg = perLeg || 5;
        var candles = [];
        for (var p = 0; p < points.length - 1; p++) {
            var from = points[p], to = points[p + 1];
            var legRange = Math.abs(to - from) || 1;
            for (var i = 0; i < perLeg; i++) {
                var t0 = i / perLeg, t1 = (i + 1) / perLeg;
                var open = from + (to - from) * t0;
                var close = from + (to - from) * t1;
                var wick = (legRange / perLeg) * 0.5;
                var high = Math.max(open, close) + wick * (0.2 + Math.random() * 0.6);
                var low = Math.min(open, close) - wick * (0.2 + Math.random() * 0.6);
                candles.push({ open: round2(open), high: round2(high), low: round2(low), close: round2(close) });
            }
        }
        return candles;
    }

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        return node;
    }

    function formatPrice(v) {
        return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function renderMiniChart(container, candles, opts) {
        opts = opts || {};
        var W = opts.width || 320, H = opts.height || 150;
        var pad = 10;
        var n = candles.length;
        var lo = Infinity, hi = -Infinity;
        candles.forEach(function (c) {
            if (c.low < lo) lo = c.low;
            if (c.high > hi) hi = c.high;
        });
        var range = (hi - lo) || 1;
        var plotH = H - pad * 2;
        var step = (W - pad * 2) / n;
        var bodyW = Math.max(1.5, Math.min(step * 0.6, 10));

        function y(v) { return pad + (1 - (v - lo) / range) * plotH; }

        var svg = svgEl("svg", {
            viewBox: "0 0 " + W + " " + H, class: "pattern-chart-svg", preserveAspectRatio: "none",
        });

        if (opts.patternLength) {
            var bandX = pad + step * opts.patternStartIndex;
            var bandW = step * opts.patternLength;
            svg.appendChild(svgEl("rect", {
                x: bandX, y: 0, width: bandW, height: H,
                fill: ACCENT, "fill-opacity": "0.12",
            }));
            svg.appendChild(svgEl("text", {
                x: bandX + bandW / 2, y: 11, "text-anchor": "middle",
                "font-size": "8", fill: ACCENT, "font-family": "var(--mono)", "font-weight": "700",
            })).textContent = "MẪU HÌNH";
        }

        candles.forEach(function (c, i) {
            var up = c.close >= c.open;
            var color = up ? UP : DOWN;
            var x = pad + step * (i + 0.5);
            var top = y(Math.max(c.open, c.close));
            var bodyH = Math.max(1, Math.abs(y(c.open) - y(c.close)));

            svg.appendChild(svgEl("line", {
                x1: x, x2: x, y1: y(c.high), y2: y(c.low), stroke: color, "stroke-width": "1.4",
            }));
            svg.appendChild(svgEl("rect", {
                x: x - bodyW / 2, y: top, width: bodyW, height: bodyH,
                rx: 1, fill: color,
            }));
        });

        // Same hover crosshair + price badge treatment used throughout the app (game chart,
        // heatmap sparklines, candlestick pattern library).
        var vLine = svgEl("line", { y1: 0, y2: H, stroke: GRID, "stroke-opacity": "0.55", "stroke-dasharray": "3 3" });
        var hLine = svgEl("line", { x1: pad, x2: W - pad, stroke: GRID, "stroke-opacity": "0.55", "stroke-dasharray": "3 3" });
        var badgeRect = svgEl("rect", { rx: 4, fill: HOVER_BADGE_BG });
        var badgeText = svgEl("text", {
            "text-anchor": "middle", "dominant-baseline": "middle", "font-size": "10",
            "font-weight": "700", fill: HOVER_BADGE_TEXT, "font-family": "var(--mono)",
        });
        var hoverGroup = svgEl("g", { style: "display:none" });
        [vLine, hLine, badgeRect, badgeText].forEach(function (el) { hoverGroup.appendChild(el); });
        svg.appendChild(hoverGroup);

        var overlay = svgEl("rect", { x: 0, y: 0, width: W, height: H, fill: "transparent" });
        svg.appendChild(overlay);

        overlay.addEventListener("pointermove", function (e) {
            var box = svg.getBoundingClientRect();
            if (!box.width) return;
            var relX = (e.clientX - box.left) / box.width * W;
            var idx = Math.max(0, Math.min(n - 1, Math.floor((relX - pad) / step)));
            var c = candles[idx];
            var px = pad + step * (idx + 0.5);
            var py = y(c.close);

            vLine.setAttribute("x1", px);
            vLine.setAttribute("x2", px);
            hLine.setAttribute("y1", py);
            hLine.setAttribute("y2", py);

            var label = formatPrice(c.close);
            badgeText.textContent = label;
            var bw = Math.max(40, label.length * 6 + 12);
            var bx = Math.min(Math.max(px - bw / 2, 2), W - bw - 2);
            var byTop = Math.min(Math.max(py - 9, 2), H - 20);
            badgeRect.setAttribute("x", bx);
            badgeRect.setAttribute("y", byTop);
            badgeRect.setAttribute("width", bw);
            badgeRect.setAttribute("height", 18);
            badgeText.setAttribute("x", bx + bw / 2);
            badgeText.setAttribute("y", byTop + 9);

            hoverGroup.style.display = "block";
        });
        overlay.addEventListener("pointerleave", function () { hoverGroup.style.display = "none"; });

        container.appendChild(svg);
    }

    function buildCard(pattern) {
        var card = document.createElement("div");
        card.className = "pattern-card";
        card.dataset.tags = pattern.tags.join(",");

        var chartBox = document.createElement("div");
        chartBox.className = "pattern-chart technical-chart";
        renderMiniChart(chartBox, buildSwingCandles(pattern.points, 5), { width: 320, height: 150 });

        var body = document.createElement("div");
        body.className = "pattern-body";

        var tagsRow = document.createElement("div");
        tagsRow.className = "pattern-tags";
        pattern.tags.forEach(function (tag) {
            var pill = document.createElement("span");
            pill.className = "pattern-tag tag-" + tag;
            pill.textContent = TAG_LABEL[tag] || tag;
            tagsRow.appendChild(pill);
        });

        var name = document.createElement("h3");
        name.className = "pattern-name";
        name.textContent = pattern.name;

        var summary = document.createElement("p");
        summary.className = "pattern-summary";
        summary.textContent = pattern.summary;

        var detail = document.createElement("ul");
        detail.className = "pattern-detail hidden";
        pattern.howTo.forEach(function (line) {
            var li = document.createElement("li");
            li.textContent = line;
            detail.appendChild(li);
        });

        var toggle = document.createElement("button");
        toggle.className = "pattern-toggle";
        toggle.type = "button";
        toggle.textContent = "Cách nhận diện ▾";
        toggle.addEventListener("click", function () {
            var expanded = !detail.classList.contains("hidden");
            detail.classList.toggle("hidden", expanded);
            toggle.textContent = expanded ? "Cách nhận diện ▾" : "Ẩn bớt ▴";
            card.classList.toggle("expanded", !expanded);
        });

        var findBtn = document.createElement("button");
        findBtn.className = "pattern-toggle pattern-find-btn";
        findBtn.type = "button";
        findBtn.textContent = "🔍 Tìm ví dụ thật";

        var example = document.createElement("div");
        example.className = "pattern-example hidden";

        findBtn.addEventListener("click", function () {
            findRealExample(pattern.id, findBtn, example);
        });

        body.appendChild(tagsRow);
        body.appendChild(name);
        body.appendChild(summary);
        body.appendChild(toggle);
        body.appendChild(detail);
        body.appendChild(findBtn);
        body.appendChild(example);

        card.appendChild(chartBox);
        card.appendChild(body);
        return card;
    }

    async function findRealExample(patternId, button, container) {
        button.disabled = true;
        var previousLabel = button.textContent;
        button.textContent = "Đang tìm…";

        try {
            var res = await fetch("/api/technical-patterns/" + patternId + "/example?asset=" + selectedAsset);
            if (!res.ok) throw new Error((await res.json()).message || "Không tìm thấy ví dụ");
            var data = await res.json();

            container.innerHTML = "";
            var chartBox = document.createElement("div");
            chartBox.className = "pattern-chart pattern-example-chart technical-chart";
            renderMiniChart(chartBox, data.candles, {
                patternStartIndex: data.patternStartIndex,
                patternLength: data.patternLength,
                width: 640,
                height: 190,
            });

            var caption = document.createElement("p");
            caption.className = "pattern-example-caption";
            var occurred = new Date(data.occurredAt);
            caption.textContent = "Phá vỡ xác nhận lúc " + occurred.toLocaleString("vi-VN") +
                " trên " + data.asset + " (khung " + data.timeframe.toUpperCase() + ")";

            var patternEnd = data.candles[data.patternStartIndex + data.patternLength - 1];
            var lastCandle = data.candles[data.candles.length - 1];
            var afterCount = data.candles.length - (data.patternStartIndex + data.patternLength);
            var outcome = document.createElement("p");
            outcome.className = "pattern-example-outcome";
            if (patternEnd.close > 0 && afterCount > 0) {
                var pct = (lastCandle.close - patternEnd.close) / patternEnd.close * 100;
                var up = pct >= 0;
                outcome.textContent = "Diễn biến " + afterCount + " nến sau phá vỡ: " + (up ? "+" : "") + pct.toFixed(2) + "%";
                outcome.classList.add(up ? "outcome-up" : "outcome-down");
            }

            container.appendChild(chartBox);
            container.appendChild(outcome);
            container.appendChild(caption);
            container.classList.remove("hidden");
            button.textContent = "🔍 Tìm ví dụ khác";
        } catch (err) {
            container.innerHTML = "";
            var errText = document.createElement("p");
            errText.className = "pattern-example-caption pattern-example-error";
            errText.textContent = "⚠️ " + err.message;
            container.appendChild(errText);
            container.classList.remove("hidden");
            button.textContent = previousLabel;
        } finally {
            button.disabled = false;
        }
    }

    /* Same reason as patterns.js: fetch first, build once. */
    async function init() {
        var grid = document.getElementById("technical-grid");
        var items;
        try {
            items = await window.CandleContent.load("technical-pattern");
        } catch (e) {
            /* No compiled-in copy to fall back to any more, so say so. Silence here
               would read as "there are no mẫu hình kỹ thuật", which is a different claim. */
            window.CandleContent.notice(grid, "Không tải được mẫu hình kỹ thuật. Thử tải lại trang.");
            return;
        }
        if (!items.length) {
            window.CandleContent.notice(grid, "Chưa có mẫu hình kỹ thuật nào.");
            return;
        }
        var filters = Array.prototype.slice.call(document.querySelectorAll("#technical-filters .pill-option"));
        window.CandlePill.attach(document.getElementById("technical-filters"), ".pill-option");
        var cards = items.map(function (p) {
            var card = buildCard(p);
            grid.appendChild(card);
            return card;
        });

        filters.forEach(function (btn) {
            btn.addEventListener("click", function () {
                filters.forEach(function (b) { b.classList.toggle("active", b === btn); });
                var filter = btn.dataset.filter;
                cards.forEach(function (card) {
                    var show = filter === "all" || card.dataset.tags.split(",").indexOf(filter) !== -1;
                    card.classList.toggle("hidden", !show);
                });
            });
        });

        var assetButtons = Array.prototype.slice.call(document.querySelectorAll("#technical-asset-pill .pill-option"));
        window.CandlePill.attach(document.getElementById("technical-asset-pill"), ".pill-option");
        assetButtons.forEach(function (btn) {
            btn.addEventListener("click", function () {
                if (btn.classList.contains("active")) return;
                assetButtons.forEach(function (b) { b.classList.toggle("active", b === btn); });
                selectedAsset = btn.dataset.asset;
            });
        });
    }

    init();
})();
