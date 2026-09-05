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

    /* The axis ticks stay compact ("81K") — there are several of them stacked down one edge,
       and losing a digit of precision costs nothing there. The reference tag is the one number
       on the whole chart a player is meant to actually read off, the same way a live ticker
       shows the full price rather than a rounded one, so it gets the real digits: thousands
       separators for readability, no decimals above $1 (a chart this size has no room to spare
       on cents that don't change which side of the line the price falls on), full precision
       below $1 where the whole number is the fraction. */
    function formatTagPrice(v) {
        if (v >= 1) return Math.round(v).toLocaleString("en-US");
        return String(v);
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
            var tagText = options.referenceLabel || formatTagPrice(options.referencePrice);
            var tagH = 13;
            var tagW = Math.max(26, tagText.length * 5.6 + 8);
            // Clamped to the SVG's own width rather than trusted to fit inside the padding —
            // a price with more digits than expected shrinks the tag's margin before it would
            // ever run past the frame.
            var tagX = Math.min(plotX1 + 2, w - tagW - 2);
            svg.appendChild(svgEl("rect", {
                x: tagX, y: ry - tagH / 2, width: tagW, height: tagH, rx: 2.5, fill: refColor,
            }));
            var tag = svgEl("text", {
                x: tagX + tagW / 2, y: ry, "text-anchor": "middle", "dominant-baseline": "middle",
                "font-size": "8.5", "font-weight": "700", fill: "#06110f", "font-family": "var(--mono)",
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
