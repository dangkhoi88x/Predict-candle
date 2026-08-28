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
        subtabs: Array.prototype.slice.call(document.querySelectorAll(".heatmap-subtab")),
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
        el.detailPrice.textContent = item.priceLabel;
        el.detailDelta.textContent = (item.change >= 0 ? "+" : "") + item.change.toFixed(2) + "% (24h)";
        el.detailDelta.className = "heatmap-detail-delta " + (item.change >= 0 ? "outcome-up" : "outcome-down");

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

    async function load(source) {
        el.refresh.disabled = true;
        el.status.textContent = "Đang tải dữ liệu thị trường…";
        el.detail.classList.add("hidden");
        try {
            await sources[source].load(el.grids[source]);
            loaded[source] = true;
            el.status.textContent = "";
            el.updated.textContent = "Cập nhật lúc " + new Date().toLocaleTimeString("vi-VN");
        } catch (err) {
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
