(function () {
    "use strict";

    var API_URL = "https://api.coingecko.com/api/v3/coins/markets" +
        "?vs_currency=usd&order=market_cap_desc&per_page=24&page=1&price_change_percentage=24h&sparkline=true";

    function formatPrice(v) {
        if (v >= 1000) return "$" + v.toLocaleString("en-US", { maximumFractionDigits: 0 });
        if (v >= 1) return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 4, maximumFractionDigits: 6 });
    }

    /* The four pairs the game has stored candles for. Anything else on the map is a coin you
       can read about here and not a round you can play, and the detail panel hides its play
       button rather than offering one. Keeping the list here rather than in the panel is
       deliberate: it is a fact about this data source's symbols, and the S&P loader answers
       the same question differently — it never sets one. */
    var PLAYABLE = { BTC: "BTCUSDT", ETH: "ETHUSDT", BNB: "BNBUSDT", SOL: "SOLUSDT" };

    function formatCompactUsd(v) {
        return "$" + new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 2 }).format(v);
    }

    window.CryptoHeatmap = {
        caption: "Diện tích ô tỉ lệ với căn bậc hai vốn hóa · màu sắc thể hiện biến động giá 24h · bấm vào 1 ô để xem biểu đồ 7 ngày · nguồn dữ liệu CoinGecko",

        load: async function (grid) {
            var res = await fetch(API_URL);
            if (!res.ok) throw new Error("HTTP " + res.status);
            var coins = await res.json();
            if (!Array.isArray(coins) || !coins.length) throw new Error("Không có dữ liệu");

            var priced = coins.filter(function (c) { return c.market_cap > 0; });
            /* Share of what is on the map, not of the whole market — the map is the top 24
               by cap and nothing here knows the total. The label says "trên bản đồ" for that
               reason: a figure called market dominance would be wrong by whatever the tail
               weighs. */
            var mappedCap = priced.reduce(function (sum, c) { return sum + c.market_cap; }, 0);

            var items = priced
                .map(function (c) {
                    return {
                        symbol: c.symbol,
                        name: c.name,
                        // sqrt-scaled so a handful of mega-cap coins (BTC, ETH) don't crush every
                        // other tile down to an unreadable sliver — still bigger-cap-bigger-tile,
                        // just compressed.
                        weight: Math.sqrt(c.market_cap),
                        change: typeof c.price_change_percentage_24h === "number" ? c.price_change_percentage_24h : 0,
                        price: c.current_price,
                        priceLabel: formatPrice(c.current_price),
                        formatPrice: formatPrice,
                        capLabel: formatCompactUsd(c.market_cap),
                        sparkline: c.sparkline_in_7d && c.sparkline_in_7d.price,
                        sparklineCaption: "Biến động giá 7 ngày gần nhất · nguồn dữ liệu CoinGecko",
                        figures: [
                            { label: "Vốn hóa", value: formatCompactUsd(c.market_cap) },
                            { label: "KL 24h", value: formatCompactUsd(c.total_volume || 0) },
                            { label: "Tỉ trọng trên bản đồ", value: (c.market_cap / mappedCap * 100).toFixed(1) + "%" },
                        ],
                        playAsset: PLAYABLE[c.symbol.toUpperCase()] || null,
                    };
                });

            window.Treemap.renderTiles(grid, items, { formatPrice: formatPrice, onTileClick: window.__showHeatmapDetail });
        },
    };
})();
