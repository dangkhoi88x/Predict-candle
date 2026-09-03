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
        neutral: "Trung tính",
        reversal: "Đảo chiều",
        continuation: "Tiếp diễn",
    };

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        return node;
    }

    function formatPrice(v) {
        if (v >= 1000) return "$" + v.toLocaleString("en-US", { maximumFractionDigits: 0 });
        if (v >= 1) return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 4, maximumFractionDigits: 6 });
    }

    function renderMiniChart(container, candles, opts) {
        opts = opts || {};
        var W = opts.width || 240, H = opts.height || 120;
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
        var bodyW = Math.max(2, Math.min(step * 0.6, 34));

        function y(v) { return pad + (1 - (v - lo) / range) * plotH; }

        var svg = svgEl("svg", {
            viewBox: "0 0 " + W + " " + H, class: "pattern-chart-svg", preserveAspectRatio: "none",
        });
        var highlight = opts.patternLength ? opts : null;

        if (highlight) {
            var bandX = pad + step * highlight.patternStartIndex;
            var bandW = step * highlight.patternLength;
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
            var bodyH = Math.max(2, Math.abs(y(c.open) - y(c.close)));

            svg.appendChild(svgEl("line", {
                x1: x, x2: x, y1: y(c.high), y2: y(c.low), stroke: color, "stroke-width": "2",
            }));
            svg.appendChild(svgEl("rect", {
                x: x - bodyW / 2, y: top, width: bodyW, height: bodyH,
                rx: 2, fill: color,
            }));
        });

        // hover crosshair + price badge, same treatment as the practice-game chart and the
        // heatmap sparklines, so the exact price at any candle is always one hover away.
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
        card.dataset.pattern = pattern.id;

        var chartBox = document.createElement("div");
        chartBox.className = "pattern-chart";
        renderMiniChart(chartBox, pattern.candles);

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
            var res = await fetch("/api/patterns/" + patternId + "/example?asset=" + selectedAsset);
            if (!res.ok) throw new Error((await res.json()).message || "Không tìm thấy ví dụ");
            var data = await res.json();

            container.innerHTML = "";
            var chartBox = document.createElement("div");
            chartBox.className = "pattern-chart pattern-example-chart";
            renderMiniChart(chartBox, data.candles, {
                patternStartIndex: data.patternStartIndex,
                patternLength: data.patternLength,
                width: 640,
                height: 190,
            });

            var caption = document.createElement("p");
            caption.className = "pattern-example-caption";
            var occurred = new Date(data.occurredAt);
            caption.textContent = "Xảy ra lúc " + occurred.toLocaleString("vi-VN") +
                " trên " + data.asset + " (khung " + data.timeframe.toUpperCase() + ")";

            var patternEnd = data.candles[data.patternStartIndex + data.patternLength - 1];
            var lastCandle = data.candles[data.candles.length - 1];
            var afterCount = data.candles.length - (data.patternStartIndex + data.patternLength);
            var outcome = document.createElement("p");
            outcome.className = "pattern-example-outcome";
            if (patternEnd.close > 0 && afterCount > 0) {
                var pct = (lastCandle.close - patternEnd.close) / patternEnd.close * 100;
                var up = pct >= 0;
                outcome.textContent = "Diễn biến " + afterCount + " nến sau đó: " + (up ? "+" : "") + pct.toFixed(2) + "%";
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

    /* Fetched before building rather than rendering twice: the filter handlers below close
       over the card list, and rebuilding under them would leave the filters driving cards
       that are no longer on the page. */
    /* The game tab asks this file to name a pattern it found mid-round, which used to mean
       reading the compiled-in array. That array is gone, so init parks what the API returned
       here for nameOf to search. */
    var loaded = [];

    async function init() {
        var grid = document.getElementById("pattern-grid");
        var items;
        try {
            items = await window.CandleContent.load("candle-pattern");
        } catch (e) {
            /* No compiled-in copy to fall back to any more, so say so. Silence here
               would read as "there are no mẫu nến", which is a different claim. */
            window.CandleContent.notice(grid, "Không tải được mẫu nến. Thử tải lại trang.");
            return;
        }
        if (!items.length) {
            window.CandleContent.notice(grid, "Chưa có mẫu nến nào.");
            return;
        }
        loaded = items;
        var filters = Array.prototype.slice.call(document.querySelectorAll("#pattern-filters .pill-option"));
        window.CandlePill.attach(document.getElementById("pattern-filters"), ".pill-option");
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

        var assetButtons = Array.prototype.slice.call(document.querySelectorAll("#pattern-asset-pill .pill-option"));
        window.CandlePill.attach(document.getElementById("pattern-asset-pill"), ".pill-option");
        assetButtons.forEach(function (btn) {
            btn.addEventListener("click", function () {
                if (btn.classList.contains("active")) return;
                assetButtons.forEach(function (b) { b.classList.toggle("active", b === btn); });
                selectedAsset = btn.dataset.asset;
            });
        });
    }

    init();

    /* The game tab names the patterns it found in a finished round, and needs both the
       Vietnamese name and a way to send the player to the full card. Kept to those two
       things — everything else about this tab stays private to it. */
    window.CandlePatterns = {
        nameOf: function (id) {
            /* Falling back to the id is not new — it already covered an unknown pattern, and
               now also covers being asked before the fetch has landed. A raw id in a label is
               poor, but it is a great deal better than throwing inside the round summary. */
            for (var i = 0; i < loaded.length; i++) {
                if (loaded[i].id === id) return loaded[i].name;
            }
            return id;
        },

        reveal: function (id) {
            document.getElementById("tab-patterns").click();

            // A filter left on "Tăng" would hide the very card we are pointing at.
            var showAll = document.querySelector('#pattern-filters .pill-option[data-filter="all"]');
            if (showAll) showAll.click();

            var card = document.querySelector('.pattern-card[data-pattern="' + id + '"]');
            if (!card) return;
            var detail = card.querySelector(".pattern-detail");
            var toggle = card.querySelector(".pattern-toggle");
            if (detail && detail.classList.contains("hidden") && toggle) toggle.click();

            card.scrollIntoView({ block: "center", behavior: "smooth" });
            card.classList.add("pattern-card-called");
            setTimeout(function () { card.classList.remove("pattern-card-called"); }, 1600);
        },
    };
})();
