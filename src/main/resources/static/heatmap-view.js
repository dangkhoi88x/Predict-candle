/**
 * Owns the Market Heatmap tab's shared chrome (status line, "updated at", refresh button,
 * caption, and the tile detail panel) and switches between the Crypto / S&P 500 sub-tabs,
 * each backed by its own loader module (window.CryptoHeatmap / window.Sp500Heatmap) from
 * heatmap.js / heatmap-sp500.js.
 */
(function () {
    "use strict";

    var sources = { crypto: window.CryptoHeatmap, sp500: window.Sp500Heatmap };

    var el = {
        subtabs: Array.prototype.slice.call(document.querySelectorAll("#heatmap-source-pill .pill-option")),
        grids: {
            crypto: document.getElementById("heatmap-grid-crypto"),
            sp500: document.getElementById("heatmap-grid-sp500"),
        },
        status: document.getElementById("heatmap-status"),
        updated: document.getElementById("heatmap-updated"),
        refresh: document.getElementById("heatmap-refresh"),
        caption: document.getElementById("heatmap-caption"),
        detail: document.getElementById("heatmap-detail"),
        detailClose: document.getElementById("heatmap-detail-close"),
        detailName: document.getElementById("heatmap-detail-name"),
        detailPrice: document.getElementById("heatmap-detail-price"),
        detailDelta: document.getElementById("heatmap-detail-delta"),
        detailChart: document.getElementById("heatmap-detail-chart"),
        detailCaption: document.getElementById("heatmap-detail-caption"),
        drilldownBar: document.getElementById("sp500-drilldown-bar"),
    };

    var current = "crypto";
    var loaded = { crypto: false, sp500: false };

    function showDetail(item) {
        el.detailName.textContent = item.symbol.toUpperCase() + " · " + item.name;
        window.CandleRolling.update(el.detailPrice, item.priceLabel);
        // Reassigning className here would drop .rolling along with the old outcome class.
        el.detailDelta.className = "heatmap-detail-delta rolling " + (item.change >= 0 ? "outcome-up" : "outcome-down");
        window.CandleRolling.update(el.detailDelta, (item.change >= 0 ? "+" : "") + item.change.toFixed(2) + "% (24h)");

        if (item.sparkline && item.sparkline.length > 1) {
            window.Treemap.renderSparkline(el.detailChart, item.sparkline, {
                width: 640, height: 150, formatPrice: item.formatPrice,
            });
            el.detailCaption.textContent = item.sparklineCaption || "Biến động giá gần đây";
        } else {
            el.detailChart.innerHTML = "";
            el.detailCaption.textContent = "Chưa có dữ liệu biểu đồ chi tiết cho mã này.";
        }

        el.detail.classList.remove("hidden");
        el.detail.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    if (el.detailClose) {
        el.detailClose.addEventListener("click", function () {
            el.detail.classList.add("hidden");
        });
    }

    window.__showHeatmapDetail = showDetail;

    /* Percentage boxes matching the shape a market-cap treemap actually takes: one dominant
       tile, a couple of mid ones, then a tail of small ones. The real tiles are positioned
       the same way, so the grid does not reflow when they replace these. */
    var SKELETON_TILES = [
        [0, 0, 46, 58], [46, 0, 30, 58], [76, 0, 24, 58],
        [0, 58, 22, 24], [22, 58, 20, 24], [42, 58, 20, 24], [62, 58, 19, 24], [81, 58, 19, 24],
        [0, 82, 15, 18], [15, 82, 14, 18], [29, 82, 15, 18], [44, 82, 14, 18],
        [58, 82, 14, 18], [72, 82, 14, 18], [86, 82, 14, 18],
    ];

    function showGridSkeleton(grid) {
        grid.innerHTML = "";
        var frag = document.createDocumentFragment();
        SKELETON_TILES.forEach(function (box) {
            var tile = document.createElement("div");
            tile.className = "skeleton heatmap-tile-skeleton";
            tile.style.left = box[0] + "%";
            tile.style.top = box[1] + "%";
            tile.style.width = box[2] + "%";
            tile.style.height = box[3] + "%";
            frag.appendChild(tile);
        });
        grid.appendChild(frag);
    }

    async function load(source) {
        el.refresh.disabled = true;
        el.status.textContent = "Đang tải dữ liệu thị trường…";
        el.detail.classList.add("hidden");
        showGridSkeleton(el.grids[source]);
        try {
            await sources[source].load(el.grids[source]);
            loaded[source] = true;
            el.status.textContent = "";
            el.updated.textContent = "Cập nhật lúc " + new Date().toLocaleTimeString("vi-VN");
        } catch (err) {
            el.grids[source].innerHTML = "";
            el.status.textContent = "Không tải được dữ liệu thị trường: " + err.message;
        } finally {
            el.refresh.disabled = false;
        }
    }

    function setActive(source) {
        current = source;
        el.detail.classList.add("hidden");
        el.subtabs.forEach(function (t) { t.classList.toggle("active", t.dataset.source === source); });
        Object.keys(el.grids).forEach(function (key) { el.grids[key].classList.toggle("hidden", key !== source); });
        if (el.drilldownBar) el.drilldownBar.classList.toggle("hidden", source !== "sp500");
        el.caption.textContent = sources[source].caption;
        if (!loaded[source]) {
            el.updated.textContent = "";
            load(source);
        }
    }

    window.CandlePill.attach(document.getElementById("heatmap-source-pill"), ".pill-option");

    el.subtabs.forEach(function (tab) {
        tab.addEventListener("click", function () {
            if (tab.classList.contains("active")) return;
            setActive(tab.dataset.source);
        });
    });

    el.refresh.addEventListener("click", function () { load(current); });

    window.__initHeatmapView = function () {
        if (window.__heatmapViewInited) return;
        window.__heatmapViewInited = true;
        el.caption.textContent = sources.crypto.caption;
        load("crypto");
    };
})();
