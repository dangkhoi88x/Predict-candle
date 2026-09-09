/**
 * S&P 500 sub-tab: sector-level treemap first (weighted by total market cap, colored by
 * cap-weighted average change), drilling into a per-stock treemap on click, which in turn
 * opens the shared chart detail panel (window.__showHeatmapDetail) on a stock click.
 */
(function () {
    "use strict";

    var API_URL = "/api/market/sp500";

    var el = {
        backBtn: document.getElementById("sp500-back"),
        breadcrumb: document.getElementById("sp500-breadcrumb"),
    };

    var allQuotes = null;
    var activeGrid = null;

    function groupBySector(quotes) {
        var bySector = {};
        quotes.forEach(function (q) {
            var bucket = bySector[q.sector] || (bySector[q.sector] = { sector: q.sector, totalCap: 0, weightedChange: 0, stocks: [] });
            bucket.totalCap += q.marketCap;
            bucket.weightedChange += q.changePercent * q.marketCap;
            bucket.stocks.push(q);
        });
        return Object.keys(bySector).map(function (key) { return bySector[key]; });
    }

    function renderSectorView(grid) {
        el.backBtn.classList.add("hidden");
        el.breadcrumb.textContent = "Bấm vào 1 ngành để xem các cổ phiếu tiêu biểu";

        var sectors = groupBySector(allQuotes);
        var items = sectors.map(function (s) {
            return {
                symbol: s.sector,
                name: s.sector,
                weight: s.totalCap,
                change: s.weightedChange / s.totalCap,
                price: s.totalCap,
                priceLabel: window.CandleFormat.compactUsd(s.totalCap),
                capLabel: window.CandleFormat.compactUsd(s.totalCap) + " (tổng vốn hóa ước tính)",
                _stocks: s.stocks,
            };
        });

        window.Treemap.renderTiles(grid, items, {
            formatPrice: window.CandleFormat.compactUsd,
            onTileClick: function (item) { renderStockView(grid, item.name, item._stocks); },
        });
    }

    function renderStockView(grid, sectorName, stocks) {
        el.backBtn.classList.remove("hidden");
        el.breadcrumb.textContent = "S&P 500 › " + sectorName;

        var items = stocks.map(function (q) {
            return {
                symbol: q.symbol,
                name: q.name,
                // sqrt-scaled so the biggest name in a sector doesn't swallow every other tile.
                weight: Math.sqrt(q.marketCap),
                change: q.changePercent,
                price: q.price,
                priceLabel: window.CandleFormat.price(q.price),
                formatPrice: window.CandleFormat.price,
                capLabel: window.CandleFormat.compactUsd(q.marketCap) + " (ước tính)",
                /* No turnover and no share: this feed carries neither, and the panel leaves
                   a row out rather than drawing it with a dash. No playAsset either — the
                   game deals crypto pairs, so there is no round to offer on a stock. */
                figures: [
                    { label: "Vốn hóa", value: window.CandleFormat.compactUsd(q.marketCap) + " (ước tính)" },
                    { label: "Ngành", value: sectorName },
                ],
                sparkline: q.sparkline,
                sparklineCaption: "Biến động giá 5 ngày gần nhất (giờ giao dịch) · nguồn dữ liệu Yahoo Finance",
            };
        });

        window.Treemap.renderTiles(grid, items, { formatPrice: window.CandleFormat.price, onTileClick: window.__showHeatmapDetail });
    }

    if (el.backBtn) {
        el.backBtn.addEventListener("click", function () {
            if (activeGrid) renderSectorView(activeGrid);
        });
    }

    window.Sp500Heatmap = {
        caption: "24 cổ phiếu tiêu biểu S&P 500, nhóm theo 11 ngành GICS · diện tích theo tổng vốn hóa ước tính trong ngành · bấm vào 1 ngành để xem cổ phiếu, bấm vào cổ phiếu để xem biểu đồ · giá & biến động cập nhật trực tiếp từ Yahoo Finance",

        load: async function (grid) {
            var res = await fetch(API_URL);
            if (!res.ok) throw new Error("HTTP " + res.status);
            var quotes = await res.json();
            if (!Array.isArray(quotes) || !quotes.length) throw new Error("Không có dữ liệu");

            allQuotes = quotes;
            activeGrid = grid;
            renderSectorView(grid);
        },
    };
})();
