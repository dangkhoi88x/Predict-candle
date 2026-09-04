/* A small candlestick renderer, shared wherever a popup or a card needs to draw a handful of
   candles rather than run a whole chart tab. Not a general charting library — it draws exactly
   one thing, an OHLC series on a plain SVG, with an optional horizontal reference line. The
   live-round history popup is the first caller; app.js's own chart and context chart predate
   this and are not rebuilt to use it, since neither was broken. */
window.CandleChart = (function () {
    "use strict";

    var SVG_NS = "http://www.w3.org/2000/svg";

    function svgEl(tag, attrs) {
        var node = document.createElementNS(SVG_NS, tag);
        for (var k in attrs) node.setAttribute(k, attrs[k]);
        return node;
    }

    function formatDayHour(iso) {
        var d = new Date(iso);
        function pad(n) { return String(n).padStart(2, "0"); }
        return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + " " + pad(d.getHours()) + "h";
    }

    function formatAxisPrice(v) {
        if (v >= 1000) return (v / 1000).toFixed(v >= 10000 ? 0 : 1) + "K";
        return v.toFixed(v >= 100 ? 0 : 2);
    }

    /**
     * Draws {@code candles} (each {time, open, high, low, close}) into {@code svg}, replacing
     * whatever was there.
     *
     * {@code options.referencePrice}, when given, draws a dashed horizontal line at that level
     * with a filled price tag at its right end — rekto.fun draws the same line at a settled
     * round's close, colored by who won, so a glance across the chart answers "green or red"
     * before reading anything else. {@code options.referenceColor} ("up" or "down") picks which
     * token colors the line and tag; omit it for a neutral line in {@code --accent} instead —
     * the line and the win/loss color are two different things and callers without a result to
     * report (there are none yet, but the module doesn't assume there won't be) get the neutral
     * one rather than a color that implies an outcome that isn't there.
     */
    function draw(svg, candles, options) {
        options = options || {};
        while (svg.firstChild) svg.removeChild(svg.firstChild);
        var n = candles.length;
        if (!n) return;

        var view = svg.viewBox.baseVal;
        var w = view && view.width ? view.width : 300;
        var h = view && view.height ? view.height : 150;
        var pad = { top: 10, right: 52, bottom: 18, left: 4 };
        var plotX0 = pad.left, plotX1 = w - pad.right;
        var plotY0 = pad.top, plotY1 = h - pad.bottom;
        var step = (plotX1 - plotX0) / n;
        var bodyW = Math.max(1.5, Math.min(step * 0.62, 14));

        var lo = Infinity, hi = -Infinity;
        candles.forEach(function (c) {
            lo = Math.min(lo, c.low); hi = Math.max(hi, c.high);
        });
        if (options.referencePrice != null) {
            lo = Math.min(lo, options.referencePrice);
            hi = Math.max(hi, options.referencePrice);
        }
        var span = (hi - lo) || 1;
        // A little headroom so the highest wick and the reference line never sit on the frame.
        lo -= span * 0.05; hi += span * 0.05; span = hi - lo;

        function cx(i) { return plotX0 + step * (i + 0.5); }
        function py(v) { return plotY1 - ((v - lo) / span) * (plotY1 - plotY0); }

        var muted = getComputedStyle(svg).getPropertyValue("--muted").trim() || "#888";
        var up = getComputedStyle(svg).getPropertyValue("--up").trim() || "#34d399";
        var down = getComputedStyle(svg).getPropertyValue("--down").trim() || "#fb7185";
        var accent = getComputedStyle(svg).getPropertyValue("--accent").trim() || "#4f8cff";

        if (options.referencePrice != null) {
            var ry = py(options.referencePrice);
            var refColor = options.referenceColor === "up" ? up
                : options.referenceColor === "down" ? down
                : accent;
            svg.appendChild(svgEl("line", {
                x1: plotX0, x2: plotX1, y1: ry, y2: ry,
                stroke: refColor, "stroke-width": "1", "stroke-dasharray": "3 3", "stroke-opacity": "0.8",
            }));

            // A filled tag at the line's right end, the same read a live ticker gives: the
            // number that matters, not just where it sits relative to the candles.
            var tagText = options.referenceLabel || formatAxisPrice(options.referencePrice);
            var tagW = Math.max(30, tagText.length * 6.5 + 8);
            var tagH = 13;
            svg.appendChild(svgEl("rect", {
                x: plotX1 + 2, y: ry - tagH / 2, width: tagW, height: tagH, rx: 2.5, fill: refColor,
            }));
            var tag = svgEl("text", {
                x: plotX1 + 2 + tagW / 2, y: ry, "text-anchor": "middle", "dominant-baseline": "middle",
                "font-size": "9", "font-weight": "700", fill: "#06110f", "font-family": "var(--mono)",
            });
            tag.textContent = tagText;
            svg.appendChild(tag);
        }

        candles.forEach(function (c, i) {
            var color = c.close >= c.open ? up : down;
            var x = cx(i);
            var yOpen = py(c.open), yClose = py(c.close);
            svg.appendChild(svgEl("line", {
                x1: x, x2: x, y1: py(c.high), y2: py(c.low), stroke: color, "stroke-width": "1",
            }));
            svg.appendChild(svgEl("rect", {
                x: x - bodyW / 2, y: Math.min(yOpen, yClose), width: bodyW,
                height: Math.max(1, Math.abs(yClose - yOpen)), rx: 1, fill: color,
            }));
        });

        // Skipped near the reference line's own price tag so the two labels never overlap.
        var refY = options.referencePrice != null ? py(options.referencePrice) : null;
        [hi, lo + span / 2, lo].forEach(function (tick) {
            var y = py(tick);
            if (refY != null && Math.abs(y - refY) < 10) return;
            var label = svgEl("text", {
                x: plotX1 + 6, y: y, "dominant-baseline": "middle",
                "font-size": "9.5", fill: muted, "font-family": "var(--mono)",
            });
            label.textContent = formatAxisPrice(tick);
            svg.appendChild(label);
        });

        var labelStep = Math.max(1, Math.round(n / 4));
        candles.forEach(function (c, i) {
            if (i % labelStep !== 0 && i !== n - 1) return;
            var t = svgEl("text", {
                x: cx(i), y: h - 4, "text-anchor": "middle",
                "font-size": "9", fill: muted, "font-family": "var(--mono)",
            });
            t.textContent = formatDayHour(c.time);
            svg.appendChild(t);
        });
    }

    return { draw: draw };
})();
