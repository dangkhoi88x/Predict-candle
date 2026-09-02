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

    var TECHNICAL_PATTERNS = [
        {
            id: "head-shoulders",
            name: "Vai Đầu Vai (Head & Shoulders)",
            tags: ["bearish", "reversal"],
            summary: "Ba đỉnh với đỉnh giữa cao nhất (đầu), hai đỉnh bên gần bằng nhau (vai) — xuất hiện cuối xu hướng tăng.",
            howTo: [
                "Xuất hiện sau một xu hướng tăng rõ ràng",
                "Vai trái: giá tạo đỉnh rồi điều chỉnh giảm",
                "Đầu: giá tạo đỉnh mới cao hơn vai trái, rồi giảm trở lại vùng đáy tương tự",
                "Vai phải: giá tạo đỉnh thấp hơn đầu, xấp xỉ vai trái, rồi giảm mạnh",
                "Xác nhận khi giá phá vỡ 'đường viền cổ' (neckline) nối hai đáy giữa các đỉnh",
            ],
            points: [90, 108, 100, 118, 100, 107, 88],
        },
        {
            id: "inverse-head-shoulders",
            name: "Vai Đầu Vai Ngược (Inverse H&S)",
            tags: ["bullish", "reversal"],
            summary: "Ba đáy với đáy giữa thấp nhất (đầu), hai đáy bên gần bằng nhau (vai) — xuất hiện cuối xu hướng giảm.",
            howTo: [
                "Xuất hiện sau một xu hướng giảm rõ ràng",
                "Vai trái: giá tạo đáy rồi hồi phục",
                "Đầu: giá tạo đáy mới thấp hơn vai trái, rồi hồi lên vùng đỉnh tương tự",
                "Vai phải: giá tạo đáy cao hơn đầu, xấp xỉ vai trái, rồi bật tăng mạnh",
                "Xác nhận khi giá phá vỡ đường viền cổ (neckline) nối hai đỉnh giữa các đáy",
            ],
            points: [110, 92, 100, 82, 100, 93, 112],
        },
        {
            id: "double-top",
            name: "Hai Đỉnh (Double Top)",
            tags: ["bearish", "reversal"],
            summary: "Giá tạo hai đỉnh gần bằng nhau với một đáy ở giữa — hình chữ M, báo hiệu đảo chiều giảm.",
            howTo: [
                "Xuất hiện sau xu hướng tăng",
                "Đỉnh 1 và đỉnh 2 xấp xỉ cùng một mức giá",
                "Có một đáy rõ ràng ('đường cổ') giữa hai đỉnh",
                "Xác nhận khi giá phá vỡ xuống dưới đường cổ",
            ],
            points: [90, 112, 98, 112, 86],
        },
        {
            id: "double-bottom",
            name: "Hai Đáy (Double Bottom)",
            tags: ["bullish", "reversal"],
            summary: "Giá tạo hai đáy gần bằng nhau với một đỉnh ở giữa — hình chữ W, báo hiệu đảo chiều tăng.",
            howTo: [
                "Xuất hiện sau xu hướng giảm",
                "Đáy 1 và đáy 2 xấp xỉ cùng một mức giá",
                "Có một đỉnh rõ ràng ('đường cổ') giữa hai đáy",
                "Xác nhận khi giá phá vỡ lên trên đường cổ",
            ],
            points: [110, 88, 102, 88, 114],
        },
        {
            id: "ascending-triangle",
            name: "Tam Giác Tăng (Ascending Triangle)",
            tags: ["bullish", "continuation"],
            summary: "Đường kháng cự nằm ngang phía trên, đáy sau cao hơn đáy trước — áp lực mua tăng dần.",
            howTo: [
                "Đường kháng cự trên gần như nằm ngang (các đỉnh xấp xỉ bằng nhau)",
                "Các đáy liên tiếp cao dần, tạo đường hỗ trợ dốc lên",
                "Thường là mẫu hình tiếp diễn trong xu hướng tăng",
                "Xác nhận khi giá phá vỡ lên trên đường kháng cự",
            ],
            points: [90, 110, 96, 109.5, 100, 109, 103, 116],
        },
        {
            id: "descending-triangle",
            name: "Tam Giác Giảm (Descending Triangle)",
            tags: ["bearish", "continuation"],
            summary: "Đường hỗ trợ nằm ngang phía dưới, đỉnh sau thấp hơn đỉnh trước — áp lực bán tăng dần.",
            howTo: [
                "Đường hỗ trợ dưới gần như nằm ngang (các đáy xấp xỉ bằng nhau)",
                "Các đỉnh liên tiếp thấp dần, tạo đường kháng cự dốc xuống",
                "Thường là mẫu hình tiếp diễn trong xu hướng giảm",
                "Xác nhận khi giá phá vỡ xuống dưới đường hỗ trợ",
            ],
            points: [110, 90, 104, 91, 100, 90.5, 97, 84],
        },
        {
            id: "symmetrical-triangle",
            name: "Tam Giác Cân (Symmetrical Triangle)",
            tags: ["bullish", "continuation"],
            summary: "Đỉnh thấp dần và đáy cao dần cùng lúc, thu hẹp về một điểm — chờ phá vỡ theo hướng xu hướng trước đó.",
            howTo: [
                "Đường kháng cự trên dốc xuống, đường hỗ trợ dưới dốc lên",
                "Biên độ dao động thu hẹp dần khi hai đường tiến sát nhau",
                "Hướng phá vỡ (lên hoặc xuống) quyết định hướng đi tiếp theo, thường theo xu hướng trước đó",
            ],
            points: [90, 112, 92, 108, 96, 104, 100, 112],
        },
        {
            id: "bull-flag",
            name: "Cờ Tăng (Bull Flag)",
            tags: ["bullish", "continuation"],
            summary: "Một đợt tăng mạnh dựng đứng (cột cờ), sau đó tích lũy đi ngang/nghiêng nhẹ xuống (lá cờ), rồi phá vỡ tiếp tục tăng.",
            howTo: [
                "Cột cờ: một đợt tăng giá mạnh, dốc trong thời gian ngắn",
                "Lá cờ: giai đoạn tích lũy đi ngang hoặc nghiêng nhẹ xuống, khối lượng thường giảm",
                "Mẫu hình tiếp diễn — xác nhận khi giá phá vỡ lên trên lá cờ theo hướng cột cờ",
            ],
            points: [90, 118, 112, 115, 109, 112, 106, 122],
        },
        {
            id: "bear-flag",
            name: "Cờ Giảm (Bear Flag)",
            tags: ["bearish", "continuation"],
            summary: "Một đợt giảm mạnh dựng đứng (cột cờ), sau đó tích lũy đi ngang/nghiêng nhẹ lên (lá cờ), rồi phá vỡ tiếp tục giảm.",
            howTo: [
                "Cột cờ: một đợt giảm giá mạnh, dốc trong thời gian ngắn",
                "Lá cờ: giai đoạn tích lũy đi ngang hoặc nghiêng nhẹ lên, khối lượng thường giảm",
                "Mẫu hình tiếp diễn — xác nhận khi giá phá vỡ xuống dưới lá cờ theo hướng cột cờ",
            ],
            points: [110, 82, 88, 85, 91, 88, 94, 78],
        },
        {
            id: "rising-wedge",
            name: "Nêm Tăng (Rising Wedge)",
            tags: ["bearish", "reversal"],
            summary: "Cả đỉnh và đáy đều tăng nhưng thu hẹp dần biên độ — động lực tăng đang yếu đi, thường phá vỡ giảm.",
            howTo: [
                "Cả đường kháng cự và đường hỗ trợ đều dốc lên",
                "Hai đường hội tụ dần (đường hỗ trợ dốc nhanh hơn đường kháng cự)",
                "Thường xuất hiện cuối xu hướng tăng và phá vỡ theo hướng giảm",
            ],
            points: [90, 100, 94, 104, 99, 107, 103, 108.5, 106, 96],
        },
        {
            id: "falling-wedge",
            name: "Nêm Giảm (Falling Wedge)",
            tags: ["bullish", "reversal"],
            summary: "Cả đỉnh và đáy đều giảm nhưng thu hẹp dần biên độ — động lực giảm đang yếu đi, thường phá vỡ tăng.",
            howTo: [
                "Cả đường kháng cự và đường hỗ trợ đều dốc xuống",
                "Hai đường hội tụ dần (đường kháng cự dốc nhanh hơn đường hỗ trợ)",
                "Thường xuất hiện cuối xu hướng giảm và phá vỡ theo hướng tăng",
            ],
            points: [110, 100, 106, 96, 101, 93, 97, 91.5, 94, 104],
        },
        {
            id: "cup-and-handle",
            name: "Cốc Tay Cầm (Cup and Handle)",
            tags: ["bullish", "continuation"],
            summary: "Giá giảm rồi hồi phục theo hình chữ U (cốc), sau đó điều chỉnh nhẹ (tay cầm), rồi phá vỡ tăng tiếp.",
            howTo: [
                "Cốc: giá giảm dần, tạo đáy tròn rồi hồi phục về gần đỉnh cũ — hình chữ U, không nhọn",
                "Tay cầm: một đợt điều chỉnh nhỏ, đi ngang hoặc nghiêng nhẹ xuống, sau khi hồi phục xong",
                "Mẫu hình tiếp diễn tăng — xác nhận khi giá phá vỡ lên trên đỉnh của cốc",
            ],
            points: [110, 100, 92, 86, 84, 86, 92, 100, 108, 104, 106, 114],
        },
        {
            id: "bos-bearish",
            name: "Break of Structure - Giảm (Bearish BOS)",
            tags: ["bearish", "reversal"],
            summary: "Giá phá vỡ xuống dưới đáy cao gần nhất (Higher Low) giữa xu hướng tăng — cảnh báo phe mua có thể đang mất quyền kiểm soát.",
            howTo: [
                "Xuất hiện trong xu hướng tăng đã hình thành ít nhất 2 đỉnh cao dần (Higher High) và đáy cao dần (Higher Low)",
                "BOS được xác nhận khi giá đóng cửa phá xuống dưới đáy cao (Higher Low) gần nhất",
                "Đây là cảnh báo cấu trúc, không phải tín hiệu vào lệnh ngay — nên chờ giá hồi lại vùng vừa phá vỡ và bị từ chối để tăng độ tin cậy",
                "Nếu sau đó giá tạo thêm một đỉnh thấp hơn (Lower High), cấu trúc chính thức chuyển sang xu hướng giảm",
            ],
            points: [90, 106, 98, 116, 104, 124, 92],
        },
        {
            id: "bos-bullish",
            name: "Break of Structure - Tăng (Bullish BOS)",
            tags: ["bullish", "reversal"],
            summary: "Giá phá vỡ lên trên đỉnh thấp gần nhất (Lower High) giữa xu hướng giảm — cảnh báo phe bán có thể đang mất quyền kiểm soát.",
            howTo: [
                "Xuất hiện trong xu hướng giảm đã hình thành ít nhất 2 đáy thấp dần (Lower Low) và đỉnh thấp dần (Lower High)",
                "BOS được xác nhận khi giá đóng cửa phá lên trên đỉnh thấp (Lower High) gần nhất",
                "Đây là cảnh báo cấu trúc, không phải tín hiệu vào lệnh ngay — nên chờ giá hồi lại vùng vừa phá vỡ và được giữ vững để tăng độ tin cậy",
                "Nếu sau đó giá tạo thêm một đáy cao hơn (Higher Low), cấu trúc chính thức chuyển sang xu hướng tăng",
            ],
            points: [110, 94, 102, 84, 96, 76, 104],
        },
        {
            id: "sfp-bullish",
            name: "Liquidity Sweep - Đáy Giả (Bullish SFP)",
            tags: ["bullish", "reversal"],
            summary: "Giá xuyên nhẹ qua đáy cũ để quét thanh khoản (dừng lỗ của phe bán khống), rồi đảo chiều tăng mạnh trở lại trên đáy đó — bẫy phe bán.",
            howTo: [
                "Xuất hiện tại một đáy (swing low) đã hình thành trước đó",
                "Giá phá xuống dưới đáy cũ một chút (thường chỉ vài %) — đây là hành động 'quét thanh khoản', không phải phá vỡ thật",
                "Ngay sau đó giá đảo chiều nhanh, đóng cửa trở lại phía trên đáy cũ trong vài nến — xác nhận đây là cái bẫy (trap), không phải breakdown",
                "Trader mắc bẫy (short tại đáy giả) buộc phải đóng lệnh, tạo thêm lực mua cho đà tăng tiếp theo",
            ],
            points: [112, 90, 98, 85, 106],
        },
        {
            id: "sfp-bearish",
            name: "Liquidity Sweep - Đỉnh Giả (Bearish SFP)",
            tags: ["bearish", "reversal"],
            summary: "Giá xuyên nhẹ qua đỉnh cũ để quét thanh khoản (dừng lỗ của phe mua), rồi đảo chiều giảm mạnh trở lại dưới đỉnh đó — bẫy phe mua.",
            howTo: [
                "Xuất hiện tại một đỉnh (swing high) đã hình thành trước đó",
                "Giá phá lên trên đỉnh cũ một chút — hành động 'quét thanh khoản', không phải breakout thật",
                "Ngay sau đó giá đảo chiều nhanh, đóng cửa trở lại phía dưới đỉnh cũ trong vài nến — xác nhận đây là cái bẫy (trap), không phải breakout",
                "Trader mắc bẫy (long tại đỉnh giả) buộc phải đóng lệnh, tạo thêm lực bán cho đà giảm tiếp theo",
            ],
            points: [88, 110, 100, 118, 94],
        },
    ];

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
        var items = await window.CandleContent.load("technical-pattern", TECHNICAL_PATTERNS);
        var grid = document.getElementById("technical-grid");
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
