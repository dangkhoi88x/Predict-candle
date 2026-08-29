/**
 * Shared squarify-treemap renderer used by both the crypto and S&P 500 heatmap tabs.
 * Exposes window.Treemap = { squarify, tileColor, renderTiles }.
 */
(function () {
    "use strict";

    var UP = "var(--up)";
    var DOWN = "var(--down)";
    var GRID = "var(--muted)";
    var HOVER_BADGE_BG = "var(--text)";
    var HOVER_BADGE_TEXT = "var(--panel)";
    var FLAT = [42, 43, 47];
    var VW = 1000, VH = 600;
    var SVG_NS = "http://www.w3.org/2000/svg";

    function squarify(items, width, height) {
        var sorted = items.filter(function (i) { return i.weight > 0; })
            .slice().sort(function (a, b) { return b.weight - a.weight; });
        var totalWeight = sorted.reduce(function (s, i) { return s + i.weight; }, 0);
        if (!sorted.length || totalWeight <= 0 || width <= 0 || height <= 0) return [];

        var scale = (width * height) / totalWeight;
        var out = [];
        var x = 0, y = 0, w = width, h = height;
        var row = [], index = 0;

        function worst(candidate, side) {
            if (!candidate.length || side <= 0) return Infinity;
            var areas = candidate.map(function (i) { return i.weight * scale; });
            var sum = areas.reduce(function (a, b) { return a + b; }, 0);
            var max = Math.max.apply(null, areas);
            var min = Math.min.apply(null, areas);
            var side2 = side * side, sum2 = sum * sum;
            return Math.max((side2 * max) / sum2, sum2 / (side2 * min));
        }

        function layoutRow(candidate, side, horizontal) {
            var sum = candidate.reduce(function (t, i) { return t + i.weight * scale; }, 0);
            var thickness = sum / side;
            var offset = 0;
            candidate.forEach(function (item) {
                var length = (item.weight * scale) / thickness;
                var tile = {
                    x: horizontal ? x + offset : x,
                    y: horizontal ? y : y + offset,
                    w: horizontal ? length : thickness,
                    h: horizontal ? thickness : length,
                    data: item,
                };
                out.push(tile);
                offset += length;
            });
            if (horizontal) { y += thickness; h -= thickness; }
            else { x += thickness; w -= thickness; }
        }

        while (index < sorted.length) {
            var horizontal = w >= h;
            var side = horizontal ? w : h;
            var next = sorted[index];

            if (!row.length || worst(row.concat([next]), side) <= worst(row, side)) {
                row.push(next);
                index += 1;
            } else {
                layoutRow(row, side, horizontal);
                row = [];
            }
        }
        if (row.length) layoutRow(row, w >= h ? w : h, w >= h);

        return out;
    }

    function tileColor(changePct) {
        var cap = 8;
        var t = Math.max(-1, Math.min(1, changePct / cap));
        if (Math.abs(t) < 0.15) {
            return "rgb(" + FLAT.join(",") + ")";
        }
        var weight = 0.3 + Math.abs(t) * 0.7;
        var base = t > 0 ? [52, 211, 153] : [251, 113, 133];
        var rgb = base.map(function (c, i) { return Math.round(FLAT[i] + (c - FLAT[i]) * weight); });
        return "rgb(" + rgb.join(",") + ")";
    }

    /**
     * items: [{ symbol, name, weight, change, price, capLabel }]
     * opts: { formatPrice(price) -> string, onTileClick(item) }
     */
    function renderTiles(container, items, opts) {
        var formatPrice = opts.formatPrice;
        var tiles = squarify(items, VW, VH);
        container.innerHTML = "";

        tiles.forEach(function (tile) {
            var d = tile.data;
            var area = tile.w * tile.h;

            var div = document.createElement("div");
            div.className = "heatmap-tile";
            if (area < 700) div.classList.add("tile-micro");
            else if (area < 2600) div.classList.add("tile-tiny");

            div.style.left = (tile.x / VW * 100) + "%";
            div.style.top = (tile.y / VH * 100) + "%";
            div.style.width = (tile.w / VW * 100) + "%";
            div.style.height = (tile.h / VH * 100) + "%";
            div.style.background = tileColor(d.change);

            var symbolEl = document.createElement("span");
            symbolEl.className = "tile-symbol";
            symbolEl.textContent = d.symbol.toUpperCase();

            var priceEl = document.createElement("span");
            priceEl.className = "tile-price";
            priceEl.textContent = formatPrice(d.price);

            var pctEl = document.createElement("span");
            pctEl.className = "tile-pct";
            pctEl.textContent = (d.change >= 0 ? "+" : "") + d.change.toFixed(2) + "%";

            div.appendChild(symbolEl);
            div.appendChild(priceEl);
            div.appendChild(pctEl);
            div.title = d.name + "\n" + formatPrice(d.price) +
                (d.capLabel ? "\nVốn hóa: " + d.capLabel : "") +
                "\n24h: " + (d.change >= 0 ? "+" : "") + d.change.toFixed(2) + "%";

            if (opts.onTileClick) {
                div.classList.add("clickable");
                div.addEventListener("click", function () { opts.onTileClick(d); });
            }

            container.appendChild(div);
        });
    }

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        return node;
    }

    /**
     * Renders a simple filled line chart from a flat array of prices (e.g. CoinGecko's
     * 7-day sparkline) — no OHLC needed, just a quick visual trend.
     */
    function renderSparkline(container, prices, opts) {
        opts = opts || {};
        var W = opts.width || 600, H = opts.height || 150;
        var pad = 6;
        container.innerHTML = "";
        if (!prices || prices.length < 2) return;

        var n = prices.length;
        var lo = Math.min.apply(null, prices), hi = Math.max.apply(null, prices);
        var range = (hi - lo) || 1;
        var color = prices[n - 1] >= prices[0] ? UP : DOWN;
        var stepX = (W - pad * 2) / (n - 1);

        function x(i) { return pad + stepX * i; }
        function y(v) { return pad + (1 - (v - lo) / range) * (H - pad * 2); }

        var linePath = "M" + x(0) + "," + y(prices[0]);
        for (var i = 1; i < n; i++) linePath += " L" + x(i) + "," + y(prices[i]);
        var areaPath = linePath + " L" + x(n - 1) + "," + (H - pad) + " L" + x(0) + "," + (H - pad) + " Z";

        var svg = svgEl("svg", { viewBox: "0 0 " + W + " " + H, preserveAspectRatio: "none", class: "sparkline-svg" });
        var gradId = "sparkgrad" + Math.random().toString(36).slice(2);

        var stop1 = svgEl("stop", { offset: "0%", "stop-color": color, "stop-opacity": "0.35" });
        var stop2 = svgEl("stop", { offset: "100%", "stop-color": color, "stop-opacity": "0" });
        var grad = svgEl("linearGradient", { id: gradId, x1: "0", y1: "0", x2: "0", y2: "1" });
        grad.appendChild(stop1);
        grad.appendChild(stop2);
        var defs = svgEl("defs", {});
        defs.appendChild(grad);
        svg.appendChild(defs);

        svg.appendChild(svgEl("path", { d: areaPath, fill: "url(#" + gradId + ")", stroke: "none" }));
        svg.appendChild(svgEl("path", {
            d: linePath, fill: "none", stroke: color,
            "stroke-width": "2", "stroke-linejoin": "round", "stroke-linecap": "round",
        }));

        // hover crosshair: vertical + horizontal dashed guides plus a price badge, same
        // treatment as the practice-game chart so users can always read off an exact value.
        var formatPrice = opts.formatPrice || function (v) { return "$" + v.toFixed(2); };
        var vLine = svgEl("line", { y1: pad, y2: H - pad, stroke: GRID, "stroke-opacity": "0.55", "stroke-dasharray": "3 3" });
        var hLine = svgEl("line", { x1: pad, x2: W - pad, stroke: GRID, "stroke-opacity": "0.55", "stroke-dasharray": "3 3" });
        var dot = svgEl("circle", { r: 4, fill: "var(--panel)", stroke: color, "stroke-width": "2" });
        var badgeRect = svgEl("rect", { rx: 4, fill: HOVER_BADGE_BG });
        var badgeText = svgEl("text", {
            "text-anchor": "middle", "dominant-baseline": "middle", "font-size": "10.5",
            "font-weight": "700", fill: HOVER_BADGE_TEXT, "font-family": "var(--mono)",
        });
        var hoverGroup = svgEl("g", { style: "display:none" });
        [vLine, hLine, dot, badgeRect, badgeText].forEach(function (el) { hoverGroup.appendChild(el); });
        svg.appendChild(hoverGroup);

        var overlay = svgEl("rect", { x: 0, y: 0, width: W, height: H, fill: "transparent" });
        svg.appendChild(overlay);

        function updateHover(clientX) {
            var box = svg.getBoundingClientRect();
            if (!box.width) return;
            var relX = (clientX - box.left) / box.width * W;
            var idx = Math.max(0, Math.min(n - 1, Math.round((relX - pad) / stepX)));
            var px = x(idx), py = y(prices[idx]);

            vLine.setAttribute("x1", px);
            vLine.setAttribute("x2", px);
            hLine.setAttribute("y1", py);
            hLine.setAttribute("y2", py);
            dot.setAttribute("cx", px);
            dot.setAttribute("cy", py);

            var label = formatPrice(prices[idx]);
            badgeText.textContent = label;
            var bw = Math.max(46, label.length * 6.5 + 14);
            var bx = Math.min(Math.max(px - bw / 2, 2), W - bw - 2);
            var byTop = Math.min(Math.max(py - 10, 2), H - 22);
            badgeRect.setAttribute("x", bx);
            badgeRect.setAttribute("y", byTop);
            badgeRect.setAttribute("width", bw);
            badgeRect.setAttribute("height", 20);
            badgeText.setAttribute("x", bx + bw / 2);
            badgeText.setAttribute("y", byTop + 10);

            hoverGroup.style.display = "block";
        }

        overlay.addEventListener("pointermove", function (e) { updateHover(e.clientX); });
        overlay.addEventListener("pointerleave", function () { hoverGroup.style.display = "none"; });

        container.appendChild(svg);
    }

    window.Treemap = {
        squarify: squarify,
        tileColor: tileColor,
        renderTiles: renderTiles,
        renderSparkline: renderSparkline,
    };
})();
