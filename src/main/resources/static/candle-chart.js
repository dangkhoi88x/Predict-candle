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

    /* Round numbers at a human interval — 1, 2 or 5 times a power of ten — rather than the
       data's own extremes. An axis exists to be read off, and 23,417.83 is not a number anybody
       reads off anything. */
    function niceTicks(lo, hi, target) {
        if (!isFinite(lo) || !isFinite(hi) || hi <= lo) return [lo];
        var raw = (hi - lo) / (target || 4);
        var mag = Math.pow(10, Math.floor(Math.log10(raw)));
        var norm = raw / mag;
        var stepSize = (norm >= 7.5 ? 10 : norm >= 3.5 ? 5 : norm >= 2.25 ? 2.5 : norm >= 1.5 ? 2 : 1) * mag;
        var first = Math.ceil(lo / stepSize) * stepSize;
        var out = [];
        /* Re-rounded on the way out because repeated addition of a fractional step accumulates
           float error, and an axis labelled 62000.000000000007 is not an axis. */
        for (var v = first; v <= hi + stepSize * 0.001; v += stepSize) {
            out.push(Math.round(v / stepSize) * stepSize);
        }
        return out;
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
     *
     * Returns the {@code frame} it drew in — the plot rectangle, the candle step, and the
     * conversions both ways between data and coordinates — or null when there was nothing to
     * draw. That is what lets a caller put something over the chart (the crosshair is the first)
     * without recomputing the geometry. Padding here is not a constant: it moves when volume
     * takes its strip out of the plot, and a second copy of that arithmetic somewhere else would
     * be wrong the first time this one changes.
     */
    function draw(svg, candles, options) {
        options = options || {};
        while (svg.firstChild) svg.removeChild(svg.firstChild);
        var n = candles.length;
        if (!n) return null;

        var view = svg.viewBox.baseVal;
        var w = view && view.width ? view.width : 300;
        var h = view && view.height ? view.height : 150;
        var pad = { top: 10, right: 52, bottom: 18, left: 4 };
        var plotX0 = pad.left, plotX1 = w - pad.right;
        var plotY0 = pad.top, plotY1 = h - pad.bottom;
        /* Volume takes its strip out of the price plot rather than growing the chart: the
           viewBox is fixed by the caller, so the candles have to make room for it. */
        var volumes = options.volumes || null;
        var volH = volumes ? (plotY1 - plotY0) * 0.18 : 0;
        var volY1 = plotY1;
        if (volumes) plotY1 -= volH + 4;
        var step = (plotX1 - plotX0) / n;
        /* Bounds, not a fixed width. A live popup showing forty candles in a small box and a
           practice round showing twenty-five across a wide card both want the widest body the
           step allows, but "widest" means something four times larger in one than the other —
           and a body clamped to 14px in the practice card leaves the chart looking like a bar
           code. Defaults are the small-chart values every existing caller was drawn with. */
        var body = options.body || {};
        var bodyW = Math.max(body.min || 1.5, Math.min(step * (body.ratio || 0.62), body.max || 14));

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

        /* A band behind the candles the caller is asking about. Drawn first so the candles sit
           on top of it — the pattern quiz would otherwise be asking "name the pattern" of a
           chart with no indication which candles it means, a far harder and different question. */
        /* Bands behind the candles, marking stretches of the x axis. One caller marks the
           pattern it is asking about; another marks three at once — what the player saw, what
           they had to guess, and the context either side — which is why this takes a list. A
           band with no colour of its own gets the accent and an outline, the shape the single
           highlight has always had. */
        var bands = options.highlight ? [options.highlight] : (options.highlights || []);
        bands.forEach(function (band) {
            if (!band || !(band.length > 0) || band.from < 0 || band.from + band.length > n) return;
            var attrs = {
                x: cx(band.from) - step / 2, y: plotY0 - 4,
                width: step * band.length, height: (volumes ? volY1 : plotY1) - plotY0 + 8,
                rx: 3, fill: band.color || accent,
                "fill-opacity": band.opacity == null ? "0.12" : band.opacity,
            };
            if (!band.color) {
                attrs.stroke = accent;
                attrs["stroke-opacity"] = "0.5";
                attrs["stroke-width"] = "1";
            }
            svg.appendChild(svgEl("rect", attrs));
        });

        /* Volume first, so candles and the average draw over it rather than under. Scaled to
           its own strip's tallest bar — volume is read as "big for this chart", never against
           the price axis it shares no units with. */
        if (volumes) {
            var maxVol = 0;
            volumes.forEach(function (v) { maxVol = Math.max(maxVol, +v || 0); });
            volumes.forEach(function (v, i) {
                if (i >= n) return;
                var barH = maxVol ? ((+v || 0) / maxVol) * volH : 0;
                svg.appendChild(svgEl("rect", {
                    x: cx(i) - bodyW / 2, y: volY1 - barH, width: bodyW, height: Math.max(barH, 0.5),
                    fill: candles[i].close >= candles[i].open ? up : down, "fill-opacity": "0.35",
                }));
            });
        }

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

        /* Each candle is a group rather than two loose shapes. Two callers need to address a
           candle after the fact — one animates the ones that just arrived, one dims the ones
           the pointer is not on — and a group is what gives them something to address. The
           groups come back on the frame. */
        var groups = candles.map(function (c, i) {
            var color = c.close >= c.open ? up : down;
            var x = cx(i);
            var yOpen = py(c.open), yClose = py(c.close);
            var g = svgEl("g", { "class": "chart-candle" });
            g.appendChild(svgEl("line", {
                x1: x, x2: x, y1: py(c.high), y2: py(c.low), stroke: color,
                "stroke-width": options.wickWidth || "1",
            }));
            g.appendChild(svgEl("rect", {
                x: x - bodyW / 2, y: Math.min(yOpen, yClose), width: bodyW,
                height: Math.max(1, Math.abs(yClose - yOpen)),
                rx: Math.min(options.bodyRadius == null ? 1 : options.bodyRadius, bodyW * 0.18),
                fill: color,
            }));
            /* Candles at or after enterFrom are the ones this draw is adding, so they rise into
               place; the ones already on screen must not move, or every reveal would re-animate
               the whole chart. */
            if (options.enterFrom != null && i >= options.enterFrom) {
                g.style.transformBox = "fill-box";
                g.style.transformOrigin = "bottom center";
                g.style.animation = "candle-rise var(--duration-enter) var(--ease-out) both";
            }
            svg.appendChild(g);
            return g;
        });

        /* The price axis. Three levels off the data's own extremes by default, which is right
           for a chart the size of a postage stamp; options.ticks asks instead for round numbers
           at a readable interval, plus a rule at each. A big chart wants those — "23K" printed
           three times because the range is narrower than the rounding is not an axis — and a
           small one has no room for them. */
        var refY = options.referencePrice != null ? py(options.referencePrice) : null;
        var axisFormat = options.axisFormat || formatAxisPrice;
        var levels = options.ticks ? niceTicks(lo, hi, options.ticks) : [hi, lo + span / 2, lo];
        levels.forEach(function (tick) {
            var y = py(tick);
            if (y < plotY0 - 1 || y > plotY1 + 1) return;
            if (options.ticks) {
                svg.appendChild(svgEl("line", {
                    x1: plotX0, x2: plotX1, y1: y, y2: y, stroke: muted,
                    "stroke-opacity": "0.16", "stroke-dasharray": "2 4",
                }));
            }
            // Skipped near the reference line's own price tag so the two labels never overlap.
            if (refY != null && Math.abs(y - refY) < (options.ticks ? 16 : 10)) return;
            var label = svgEl("text", {
                x: plotX1 + 6, y: y, "dominant-baseline": "middle",
                "font-size": options.axisFontSize || "9.5", fill: muted, "font-family": "var(--mono)",
            });
            label.textContent = axisFormat(tick);
            svg.appendChild(label);
        });

        /* Overlay lines in price space — moving averages, and anything else shaped like them.
           Drawn in neutral colours rather than up/down ones: a line across a chart reports a
           reading, not a verdict, and green would read as a signal it is not making.

           Nulls are gaps, not zeroes. A moving average has no value until it has a full period
           behind it, and joining through those would drag the line to the bottom of the chart. */
        (options.lines || []).forEach(function (line) {
            if (!line || !line.values) return;
            var points = [];
            line.values.forEach(function (v, i) {
                if (v == null || i >= n) return;
                points.push(cx(i).toFixed(1) + "," + py(+v).toFixed(1));
            });
            if (points.length < 2) return;
            svg.appendChild(svgEl("polyline", {
                points: points.join(" "), fill: "none", stroke: line.color || accent,
                "stroke-width": line.width || "1.6", "stroke-linejoin": "round",
                "stroke-opacity": line.opacity || "0.85",
            }));
        });

        /* A labelled divider at a candle boundary. The practice round puts one where guessing
           stopped and the "what happened next" candles begin — without it the reveal reads as
           part of what was being guessed. */
        if (options.marker && options.marker.index > 0 && options.marker.index < n) {
            var mx = plotX0 + step * options.marker.index;
            svg.appendChild(svgEl("line", {
                x1: mx, x2: mx, y1: plotY0, y2: plotY1, stroke: accent,
                "stroke-opacity": "0.55", "stroke-width": "1.4", "stroke-dasharray": "5 4",
            }));
            if (options.marker.label) {
                var ml = svgEl("text", {
                    x: mx + 5, y: plotY0 + 10, "font-size": "9.5", fill: accent,
                    "font-family": "var(--mono)", "font-weight": "600",
                });
                ml.textContent = options.marker.label;
                svg.appendChild(ml);
            }
        }

        /* No time axis when the candles carry no time. The daily challenge is the caller that
           needs this: sending dates with a round the player is still guessing would hand them
           the period to go and look up, so its candles arrive without one. Drawing the axis
           anyway turned every missing date into "01/01 08h", which is worse than no axis —
           it is a wrong one. */
        /* options.timeAt(i) lets a caller label the axis from something other than the candle:
           the practice round's candles carry no timestamp — a date is the answer — but it does
           know the window's start and the step between candles, so it can still say what hour
           each one is. Without it the axis reads the candle's own time, and draws nothing when
           there is none. */
        var timeAt = options.timeAt || function (i) {
            return candles[i].time == null ? null : formatDayHour(candles[i].time);
        };
        var labelStep = Math.max(1, Math.round(n / (options.timeLabels || 4)));
        candles.forEach(function (c, i) {
            if (i % labelStep !== 0 && i !== n - 1) return;
            var label = timeAt(i);
            if (label == null) return;
            var t = svgEl("text", {
                x: cx(i), y: h - (options.timeAxisInset || 4), "text-anchor": "middle",
                "font-size": options.timeFontSize || "9", fill: muted, "font-family": "var(--mono)",
            });
            t.textContent = label;
            svg.appendChild(t);
        });

        return {
            n: n, step: step, candles: candles, groups: groups, bodyW: bodyW,
            plotX0: plotX0, plotX1: plotX1, plotY0: plotY0, plotY1: plotY1,
            /* Where the drawing actually ends, which is below plotY1 whenever volume took its
               strip. The crosshair reads this rather than plotY1: a vertical line that stopped
               at the price plot's floor would leave the volume bars hanging under a line that
               visibly does not reach them. */
            bottom: volumes ? volY1 : plotY1,
            lo: lo, hi: hi,
            cx: cx, py: py,
            /* Which candle a horizontal position falls on, clamped rather than null past either
               end: a pointer a few pixels into the padding is still pointing at the edge candle,
               and blanking the readout there reads as the chart flickering. */
            indexAt: function (x) {
                var i = Math.floor((x - plotX0) / step);
                return Math.max(0, Math.min(n - 1, i));
            },
            priceAt: function (y) {
                return lo + ((plotY1 - y) / (plotY1 - plotY0)) * span;
            },
        };
    }

    var CROSSHAIR = "chart-crosshair";

    /**
     * Tracks the pointer across a chart already drawn by {@link draw}: a vertical line on the
     * candle under it, a horizontal line at the level it is at, and a tag for each.
     *
     * {@code point} is in the SVG's own viewBox units, not screen pixels — converting is the
     * caller's job because only the caller knows which element the event came from, and these
     * charts are drawn with `preserveAspectRatio="none"`, so x and y are scaled by *different*
     * factors and `getScreenCTM().inverse()` is the only conversion that survives a resize.
     *
     * The vertical line snaps to a candle; the horizontal one follows the pointer freely. That
     * asymmetry is the point: between two candles there is no data to report, but between two
     * candles' prices there is a perfectly real level someone is measuring against.
     *
     * The nodes are built once and then only have their attributes rewritten. A pointer moves
     * faster than the screen refreshes, and rebuilding this on every move would put a few
     * hundred DOM insertions between the mouse and the picture.
     *
     * Returns the index of the candle under the pointer, so the caller can name it.
     */
    function crosshair(svg, frame, point, options) {
        if (!frame) return null;
        options = options || {};

        var g = svg.querySelector("." + CROSSHAIR);
        if (!g) {
            g = svgEl("g", { class: CROSSHAIR, "pointer-events": "none" });
            g.appendChild(svgEl("line", {
                "data-part": "v", stroke: "var(--muted)", "stroke-width": "1",
                "stroke-dasharray": "2 3", "stroke-opacity": "0.9",
            }));
            g.appendChild(svgEl("line", {
                "data-part": "h", stroke: "var(--muted)", "stroke-width": "1",
                "stroke-dasharray": "2 3", "stroke-opacity": "0.9",
            }));
            g.appendChild(svgEl("rect", { "data-part": "price-bg", rx: 2.5, fill: "var(--text)" }));
            g.appendChild(svgEl("text", {
                "data-part": "price", "text-anchor": "middle", "dominant-baseline": "middle",
                "font-size": "8.5", "font-weight": "700", fill: "var(--bg)",
                "font-family": "var(--mono)",
            }));
            g.appendChild(svgEl("rect", { "data-part": "time-bg", rx: 2.5, fill: "var(--text)" }));
            g.appendChild(svgEl("text", {
                "data-part": "time", "text-anchor": "middle", "dominant-baseline": "middle",
                "font-size": "8.5", "font-weight": "700", fill: "var(--bg)",
                "font-family": "var(--mono)",
            }));
        }
        // Appended (or moved back) last on every call: draw() empties the svg, and anything the
        // caller redraws underneath would otherwise end up painted over the crosshair.
        svg.appendChild(g);

        function part(name) { return g.querySelector('[data-part="' + name + '"]'); }

        /* A second pane below the chart (RSI) is *told* which candle rather than asked to work
           it out from an x belonging to a different frame. The two panes do share plotX0 and
           step today, so deriving it would happen to work — which is exactly the kind of
           coincidence that breaks silently the day one of them gets different padding. */
        var index = options.index != null
            ? Math.max(0, Math.min(frame.n - 1, options.index))
            : frame.indexAt(point.x);
        var vx = frame.cx(index);

        part("v").setAttribute("x1", vx);
        part("v").setAttribute("x2", vx);
        part("v").setAttribute("y1", frame.plotY0);
        part("v").setAttribute("y2", frame.bottom != null ? frame.bottom : frame.plotY1);

        var showLevel = !options.verticalOnly;
        part("h").setAttribute("visibility", showLevel ? "visible" : "hidden");
        if (showLevel) {
            var hy = Math.max(frame.plotY0, Math.min(frame.plotY1, point.y));
            part("h").setAttribute("x1", frame.plotX0);
            part("h").setAttribute("x2", frame.plotX1);
            part("h").setAttribute("y1", hy);
            part("h").setAttribute("y2", hy);
        }

        tag(part("price-bg"), part("price"),
            showLevel ? formatTagPrice(frame.priceAt(hy)) : "",
            frame.plotX1 + 2, showLevel ? hy : 0, "right");
        // No time tag when the candles carry no time — same rule the axis follows, and for the
        // same reason: an invented date is worse than an absent one.
        tag(part("time-bg"), part("time"),
            showLevel && options.time != null ? formatDayHour(options.time) : "",
            vx, (frame.bottom != null ? frame.bottom : frame.plotY1) + 9, "center");

        return index;
    }

    /** Positions one of the crosshair's two labels, or hides it when there is nothing to say. */
    function tag(bg, text, content, x, y, align) {
        var visible = content !== "";
        bg.setAttribute("visibility", visible ? "visible" : "hidden");
        text.setAttribute("visibility", visible ? "visible" : "hidden");
        if (!visible) return;

        var h = 13;
        var w = Math.max(26, content.length * 5.6 + 8);
        var x0 = align === "center" ? x - w / 2 : x;
        bg.setAttribute("x", x0);
        bg.setAttribute("y", y - h / 2);
        bg.setAttribute("width", w);
        bg.setAttribute("height", h);
        text.setAttribute("x", x0 + w / 2);
        text.setAttribute("y", y);
        text.textContent = content;
    }

    /** Takes the crosshair off a chart — on pointerleave, and whenever it should not be shown. */
    function clearCrosshair(svg) {
        var g = svg.querySelector("." + CROSSHAIR);
        if (g) g.remove();
    }

    /**
     * A single line on its own scale, for a reading that is not a price — RSI is the first.
     *
     * Deliberately not squeezed into the price chart: an oscillator bounded 0-100 shares no
     * units with a candle, and overlaying it either flattens the candles or leaves the line as a
     * meaningless squiggle across them. It gets its own pane and its own axis.
     */
    function drawIndicator(svg, values, options) {
        options = options || {};
        while (svg.firstChild) svg.removeChild(svg.firstChild);
        var n = values.length;
        if (!n) return null;

        var view = svg.viewBox.baseVal;
        var w = view && view.width ? view.width : 300;
        var h = view && view.height ? view.height : 80;
        var pad = { top: 6, right: 52, bottom: 6, left: 4 };
        var plotX0 = pad.left, plotX1 = w - pad.right;
        var plotY0 = pad.top, plotY1 = h - pad.bottom;
        var step = (plotX1 - plotX0) / n;

        var lo = options.min != null ? options.min : 0;
        var hi = options.max != null ? options.max : 100;
        var span = (hi - lo) || 1;

        function cx(i) { return plotX0 + step * (i + 0.5); }
        function py(v) { return plotY1 - ((v - lo) / span) * (plotY1 - plotY0); }

        var muted = getComputedStyle(svg).getPropertyValue("--muted").trim() || "#888";
        var accent = getComputedStyle(svg).getPropertyValue("--accent").trim() || "#4f8cff";

        // The levels the reading is actually judged against, labelled, so the line means
        // something without a legend somewhere else on the page.
        (options.guides || []).forEach(function (level) {
            var y = py(level);
            svg.appendChild(svgEl("line", {
                x1: plotX0, x2: plotX1, y1: y, y2: y, stroke: muted,
                "stroke-width": "1", "stroke-dasharray": "3 4", "stroke-opacity": "0.45",
            }));
            var label = svgEl("text", {
                x: plotX1 + 6, y: y, "dominant-baseline": "middle",
                "font-size": "9.5", fill: muted, "font-family": "var(--mono)",
            });
            label.textContent = String(level);
            svg.appendChild(label);
        });

        var points = [];
        values.forEach(function (v, i) {
            if (v == null) return;
            points.push(cx(i).toFixed(1) + "," + py(+v).toFixed(1));
        });
        if (points.length > 1) {
            svg.appendChild(svgEl("polyline", {
                points: points.join(" "), fill: "none", stroke: accent,
                "stroke-width": "1.6", "stroke-linejoin": "round",
            }));
        }

        // Same shape draw() returns, so the crosshair can run the pointer's candle down through
        // this pane as well without knowing which of the two it is drawing into.
        return {
            n: n, step: step,
            plotX0: plotX0, plotX1: plotX1, plotY0: plotY0, plotY1: plotY1,
            cx: cx, py: py,
            indexAt: function (x) {
                var i = Math.floor((x - plotX0) / step);
                return Math.max(0, Math.min(n - 1, i));
            },
        };
    }

    return {
        draw: draw,
        drawIndicator: drawIndicator,
        crosshair: crosshair,
        clearCrosshair: clearCrosshair,
    };
})();
