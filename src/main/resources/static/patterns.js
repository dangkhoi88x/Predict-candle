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

    // Hand-crafted illustrative OHLC — shaped to clearly demonstrate each pattern, not real
    // market data. Prices sit around 100 purely so the shapes read cleanly at any scale.
    var PATTERNS = [
        {
            id: "doji",
            name: "Doji",
            tags: ["neutral", "reversal"],
            summary: "Giá mở và đóng gần như bằng nhau — phe mua và bán giằng co bất phân thắng bại.",
            howTo: [
                "Giá mở cửa và đóng cửa gần như trùng nhau (thân nến rất nhỏ)",
                "Bóng trên và bóng dưới có thể dài, thể hiện giá đã dao động mạnh trong phiên",
                "Ý nghĩa phụ thuộc vào xu hướng trước đó: xuất hiện sau xu hướng mạnh thường báo hiệu khả năng đảo chiều hoặc tạm nghỉ",
            ],
            candles: [
                { open: 100, close: 101.2, high: 101.6, low: 99.7 },
                { open: 101.2, close: 102.3, high: 102.7, low: 100.9 },
                { open: 102.3, close: 102.42, high: 104.5, low: 100.3 },
            ],
        },
        {
            id: "hammer",
            name: "Hammer (Búa)",
            tags: ["bullish", "reversal"],
            summary: "Bóng dưới dài, thân nhỏ ở đỉnh, xuất hiện sau xu hướng giảm — lực mua đang quay lại.",
            howTo: [
                "Xuất hiện sau một xu hướng giảm rõ ràng",
                "Bóng dưới dài ít nhất gấp 2 lần thân nến",
                "Thân nến nhỏ, nằm gần đỉnh của toàn bộ khoảng dao động",
                "Bóng trên rất ngắn hoặc gần như không có",
            ],
            candles: [
                { open: 105, close: 103, high: 105.3, low: 102.8 },
                { open: 103, close: 101, high: 103.2, low: 100.8 },
                { open: 101, close: 101.6, high: 101.9, low: 97.5 },
            ],
        },
        {
            id: "hanging-man",
            name: "Hanging Man (Người treo cổ)",
            tags: ["bearish", "reversal"],
            summary: "Hình dạng giống hệt Hammer nhưng xuất hiện sau xu hướng tăng — cảnh báo lực bán đang tích lũy.",
            howTo: [
                "Xuất hiện sau một xu hướng tăng rõ ràng (khác Hammer ở điểm này)",
                "Bóng dưới dài ít nhất gấp 2 lần thân nến",
                "Thân nến nhỏ, nằm gần đỉnh của toàn bộ khoảng dao động",
                "Cần nến xác nhận giảm giá ngay sau đó để tăng độ tin cậy",
            ],
            candles: [
                { open: 97, close: 99, high: 99.2, low: 96.8 },
                { open: 99, close: 101, high: 101.2, low: 98.8 },
                { open: 101, close: 100.4, high: 101.6, low: 97.0 },
            ],
        },
        {
            id: "shooting-star",
            name: "Shooting Star (Sao băng)",
            tags: ["bearish", "reversal"],
            summary: "Bóng trên dài, thân nhỏ ở đáy, xuất hiện sau xu hướng tăng — lực mua đang đuối sức.",
            howTo: [
                "Xuất hiện sau một xu hướng tăng rõ ràng",
                "Bóng trên dài ít nhất gấp 2 lần thân nến",
                "Thân nến nhỏ, nằm gần đáy của toàn bộ khoảng dao động",
                "Bóng dưới rất ngắn hoặc gần như không có",
            ],
            candles: [
                { open: 97, close: 99, high: 99.2, low: 96.8 },
                { open: 99, close: 101, high: 101.2, low: 98.8 },
                { open: 101, close: 100.5, high: 104.8, low: 100.3 },
            ],
        },
        {
            id: "marubozu",
            name: "Marubozu",
            tags: ["bullish", "continuation"],
            summary: "Thân nến dài kín, gần như không có bóng — phe mua (hoặc bán) áp đảo tuyệt đối suốt phiên.",
            howTo: [
                "Không có (hoặc rất ít) bóng nến ở cả hai đầu",
                "Marubozu tăng: mở ở đáy, đóng ở đỉnh phiên — lực mua áp đảo hoàn toàn",
                "Marubozu giảm: mở ở đỉnh, đóng ở đáy phiên — lực bán áp đảo hoàn toàn",
                "Thường báo hiệu xu hướng hiện tại sẽ còn tiếp diễn",
            ],
            candles: [
                { open: 99.5, close: 100, high: 100.3, low: 99.3 },
                { open: 100, close: 105, high: 105.1, low: 99.9 },
            ],
        },
        {
            id: "bullish-engulfing",
            name: "Bullish Engulfing (Nhấn chìm tăng)",
            tags: ["bullish", "reversal"],
            summary: "Nến xanh lớn 'nuốt chửng' toàn bộ thân nến đỏ trước đó — phe mua giành lại quyền kiểm soát.",
            howTo: [
                "Xuất hiện sau xu hướng giảm",
                "Nến 1: thân đỏ (giảm)",
                "Nến 2: thân xanh, mở cửa thấp hơn (hoặc bằng) giá đóng cửa nến 1, đóng cửa cao hơn giá mở cửa nến 1",
                "Thân nến 2 bao trùm hoàn toàn thân nến 1",
            ],
            candles: [
                { open: 102, close: 100.5, high: 102.2, low: 100.3 },
                { open: 100, close: 103, high: 103.2, low: 99.8 },
            ],
        },
        {
            id: "bearish-engulfing",
            name: "Bearish Engulfing (Nhấn chìm giảm)",
            tags: ["bearish", "reversal"],
            summary: "Nến đỏ lớn 'nuốt chửng' toàn bộ thân nến xanh trước đó — phe bán giành lại quyền kiểm soát.",
            howTo: [
                "Xuất hiện sau xu hướng tăng",
                "Nến 1: thân xanh (tăng)",
                "Nến 2: thân đỏ, mở cửa cao hơn (hoặc bằng) giá đóng cửa nến 1, đóng cửa thấp hơn giá mở cửa nến 1",
                "Thân nến 2 bao trùm hoàn toàn thân nến 1",
            ],
            candles: [
                { open: 100.5, close: 102, high: 102.2, low: 100.3 },
                { open: 103, close: 100, high: 103.2, low: 99.8 },
            ],
        },
        {
            id: "piercing-line",
            name: "Piercing Line (Đường xuyên thấu)",
            tags: ["bullish", "reversal"],
            summary: "Nến xanh mở cửa thấp nhưng đóng cửa xuyên sâu vào giữa thân nến đỏ trước đó.",
            howTo: [
                "Xuất hiện sau xu hướng giảm",
                "Nến 1: thân đỏ dài",
                "Nến 2: mở cửa thấp hơn giá thấp nhất nến 1, nhưng đóng cửa vượt qua điểm giữa thân nến 1",
                "Khác Bullish Engulfing ở chỗ không bao trùm hoàn toàn, chỉ 'xuyên' qua điểm giữa",
            ],
            candles: [
                { open: 105, close: 101, high: 105.2, low: 100.8 },
                { open: 100.5, close: 103.5, high: 103.7, low: 100.3 },
            ],
        },
        {
            id: "dark-cloud-cover",
            name: "Dark Cloud Cover (Mây đen bao phủ)",
            tags: ["bearish", "reversal"],
            summary: "Nến đỏ mở cửa cao nhưng đóng cửa xuyên sâu vào giữa thân nến xanh trước đó.",
            howTo: [
                "Xuất hiện sau xu hướng tăng",
                "Nến 1: thân xanh dài",
                "Nến 2: mở cửa cao hơn giá cao nhất nến 1, nhưng đóng cửa xuyên xuống dưới điểm giữa thân nến 1",
                "Khác Bearish Engulfing ở chỗ không bao trùm hoàn toàn, chỉ 'xuyên' qua điểm giữa",
            ],
            candles: [
                { open: 100, close: 104, high: 104.2, low: 99.8 },
                { open: 104.5, close: 101.5, high: 104.7, low: 101.3 },
            ],
        },
        {
            id: "morning-star",
            name: "Morning Star (Sao Mai)",
            tags: ["bullish", "reversal"],
            summary: "Ba nến: giảm mạnh → do dự → tăng mạnh trở lại — dấu hiệu đảo chiều tăng kinh điển.",
            howTo: [
                "Nến 1: thân đỏ dài, tiếp diễn xu hướng giảm",
                "Nến 2: thân nhỏ (có thể là Doji), mở cửa gap xuống — thể hiện sự do dự",
                "Nến 3: thân xanh dài, đóng cửa đi sâu vào thân nến 1",
                "Độ tin cậy càng cao nếu nến 3 đóng cửa vượt quá điểm giữa thân nến 1",
            ],
            candles: [
                { open: 106, close: 102, high: 106.2, low: 101.8 },
                { open: 101, close: 100.7, high: 101.3, low: 100.3 },
                { open: 101.2, close: 104.5, high: 104.7, low: 101.0 },
            ],
        },
        {
            id: "evening-star",
            name: "Evening Star (Sao Hôm)",
            tags: ["bearish", "reversal"],
            summary: "Ba nến: tăng mạnh → do dự → giảm mạnh trở lại — dấu hiệu đảo chiều giảm kinh điển.",
            howTo: [
                "Nến 1: thân xanh dài, tiếp diễn xu hướng tăng",
                "Nến 2: thân nhỏ (có thể là Doji), mở cửa gap lên — thể hiện sự do dự",
                "Nến 3: thân đỏ dài, đóng cửa đi sâu vào thân nến 1",
                "Độ tin cậy càng cao nếu nến 3 đóng cửa vượt quá điểm giữa thân nến 1",
            ],
            candles: [
                { open: 98, close: 102, high: 102.2, low: 97.8 },
                { open: 103, close: 103.3, high: 103.7, low: 102.7 },
                { open: 102.8, close: 99.5, high: 103.0, low: 99.3 },
            ],
        },
        {
            id: "three-white-soldiers",
            name: "Three White Soldiers (Ba chàng lính trắng)",
            tags: ["bullish", "continuation"],
            summary: "Ba nến xanh liên tiếp, mỗi nến đóng cửa cao hơn — xu hướng tăng vững chắc, ít bóng nến.",
            howTo: [
                "Ba nến xanh liên tiếp, mỗi nến đóng cửa cao hơn nến trước",
                "Mỗi nến mở cửa nằm trong thân nến trước đó",
                "Bóng nến ngắn ở cả hai đầu — thể hiện lực mua ổn định, không bị giằng co",
            ],
            candles: [
                { open: 100, close: 102.5, high: 102.7, low: 99.8 },
                { open: 102, close: 104.5, high: 104.7, low: 101.8 },
                { open: 104, close: 106.5, high: 106.7, low: 103.8 },
            ],
        },
        {
            id: "three-black-crows",
            name: "Three Black Crows (Ba con quạ đen)",
            tags: ["bearish", "continuation"],
            summary: "Ba nến đỏ liên tiếp, mỗi nến đóng cửa thấp hơn — xu hướng giảm vững chắc, ít bóng nến.",
            howTo: [
                "Ba nến đỏ liên tiếp, mỗi nến đóng cửa thấp hơn nến trước",
                "Mỗi nến mở cửa nằm trong thân nến trước đó",
                "Bóng nến ngắn ở cả hai đầu — thể hiện lực bán ổn định, không bị giằng co",
            ],
            candles: [
                { open: 106, close: 103.5, high: 106.2, low: 103.3 },
                { open: 104, close: 101.5, high: 104.2, low: 101.3 },
                { open: 102, close: 99.5, high: 102.2, low: 99.3 },
            ],
        },
    ];

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
    async function init() {
        var items = await window.CandleContent.load("candle-pattern", PATTERNS);
        var grid = document.getElementById("pattern-grid");
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
            for (var i = 0; i < PATTERNS.length; i++) {
                if (PATTERNS[i].id === id) return PATTERNS[i].name;
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
