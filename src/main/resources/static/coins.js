/**
 * Which CoinGecko market-cap rows are worth putting on the ticker and the heatmap.
 *
 * The top of the market-cap list is not the top of the market anybody trades: it is full of
 * dollar stablecoins (USDT, USDC, USDS, DAI, USDe, USD1…), tokenised dollar-pegged products, and
 * wrapped or staked copies of coins already on the list. Every one of them sits at $1.00 and
 * 0.00%, so on a strip of fourteen prices a third said nothing and a black tile on the heatmap
 * said less. Both callers ask for more rows than they show and keep the first real ones.
 *
 * Two tests, because neither is enough alone: names that say what they are (wrapped, staked,
 * bridged), and a price pinned to a dollar that did not move — which catches pegged products
 * whose names give nothing away. A real coin trading at exactly $1 and flat for a day is rare
 * enough to lose from a decorative strip.
 */
(function () {
    "use strict";

    var DERIVATIVE_NAME = /\b(wrapped|staked|restaked|bridged|liquid staking|tokenized|tokenised)\b/i;
    var KNOWN_STABLE = /^(usdt|usdc|usds|dai|usde|usd1|fdusd|tusd|pyusd|usdd|frax|lusd|gusd|usdp|usdtb|rlusd|bsc-usd|busd|eurc|xaut|paxg)$/i;

    function isPegged(coin) {
        var price = coin.current_price;
        var change = coin.price_change_percentage_24h;
        return typeof price === "number" && price >= 0.985 && price <= 1.015
            && (typeof change !== "number" || Math.abs(change) < 0.5);
    }

    function isNoise(coin) {
        return KNOWN_STABLE.test(coin.symbol || "")
            || DERIVATIVE_NAME.test(coin.name || "")
            || isPegged(coin);
    }

    /** The first {@code limit} rows that are neither stablecoins nor derivative copies. */
    function tradable(coins, limit) {
        return coins.filter(function (c) { return !isNoise(c); }).slice(0, limit);
    }

    window.CandleCoins = { tradable: tradable, isNoise: isNoise };
})();
