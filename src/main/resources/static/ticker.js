/**
 * Top-of-page price ticker: a marquee of the largest coins by market cap.
 *
 * Reuses the same CoinGecko endpoint as the heatmap tab, just a shorter list. The strip is
 * decorative-but-informative, so a failed fetch hides it rather than showing an error — the
 * rest of the app must never look broken because a third-party quote feed is down.
 */
(function () {
    "use strict";

    var API_URL = "https://api.coingecko.com/api/v3/coins/markets" +
        "?vs_currency=usd&order=market_cap_desc&per_page=14&page=1&price_change_percentage=24h&sparkline=false";
    var REFRESH_MS = 60000;
    var SCROLL_SPEED_PX_PER_SEC = 45;

    var bar = document.getElementById("ticker");
    var track = document.getElementById("ticker-track");
    if (!bar || !track) return;

    // One entry per rendered item so refreshes can update text in place. Rebuilding the
    // track instead would restart the CSS animation and make the strip visibly jump.
    var renderedItems = [];

    function formatPrice(v) {
        if (typeof v !== "number") return "–";
        if (v >= 1000) return "$" + v.toLocaleString("en-US", { maximumFractionDigits: 0 });
        if (v >= 1) return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 4, maximumFractionDigits: 6 });
    }

    function formatChange(pct) {
        if (typeof pct !== "number") return { text: "–", up: true };
        var up = pct >= 0;
        return { text: (up ? "▲" : "▼") + " " + Math.abs(pct).toFixed(2) + "%", up: up };
    }

    function buildItem(coin) {
        var item = document.createElement("span");
        item.className = "ticker-item";

        if (coin.image) {
            var logo = document.createElement("img");
            logo.className = "ticker-logo";
            logo.src = coin.image;
            logo.alt = "";
            logo.loading = "lazy";
            item.appendChild(logo);
        }

        var symbol = document.createElement("span");
        symbol.className = "ticker-symbol";
        symbol.textContent = (coin.symbol || "").toUpperCase();
        item.appendChild(symbol);

        var price = document.createElement("span");
        price.className = "ticker-price rolling";
        window.CandleRolling.update(price, formatPrice(coin.current_price));
        item.appendChild(price);

        var change = formatChange(coin.price_change_percentage_24h);
        var changeEl = document.createElement("span");
        changeEl.className = "ticker-change rolling " + (change.up ? "up" : "down");
        window.CandleRolling.update(changeEl, change.text);
        item.appendChild(changeEl);

        return { node: item, price: price, change: changeEl };
    }

    function updateItem(entry, coin) {
        window.CandleRolling.update(entry.price, formatPrice(coin.current_price));
        var change = formatChange(coin.price_change_percentage_24h);
        // Reassigning className here would drop .rolling along with the old direction class.
        entry.change.className = "ticker-change rolling " + (change.up ? "up" : "down");
        window.CandleRolling.update(entry.change, change.text);
    }

    function render(coins) {
        track.textContent = "";
        renderedItems = [];

        // The list is rendered twice back-to-back so a -50% translation loops seamlessly.
        // The second copy is hidden from screen readers to avoid announcing every price twice.
        for (var copy = 0; copy < 2; copy++) {
            var group = document.createElement("span");
            group.className = "ticker-group";
            if (copy === 1) group.setAttribute("aria-hidden", "true");

            coins.forEach(function (coin) {
                var entry = buildItem(coin);
                renderedItems.push(entry);
                group.appendChild(entry.node);
            });
            track.appendChild(group);
        }

        // Unhide before measuring: while the bar is still display:none the track reports a
        // scrollWidth of 0, which would leave the animation at its 0s default — visible but
        // frozen.
        bar.classList.remove("hidden");

        // Keep the scroll speed constant regardless of how wide the content ends up.
        var halfWidth = track.scrollWidth / 2;
        track.style.animationDuration = halfWidth > 0
            ? Math.round(halfWidth / SCROLL_SPEED_PX_PER_SEC) + "s"
            : "60s";
    }

    function refresh(coins) {
        // Order is market-cap ranked and stable minute to minute; if it ever isn't, fall
        // back to a full rebuild rather than showing prices against the wrong symbols.
        if (renderedItems.length !== coins.length * 2) {
            render(coins);
            return;
        }
        coins.forEach(function (coin, i) {
            updateItem(renderedItems[i], coin);
            updateItem(renderedItems[i + coins.length], coin);
        });
    }

    async function load(isFirstLoad) {
        try {
            var res = await fetch(API_URL);
            if (!res.ok) throw new Error("HTTP " + res.status);
            var coins = await res.json();
            if (!Array.isArray(coins) || !coins.length) throw new Error("empty payload");

            if (isFirstLoad) render(coins);
            else refresh(coins);
        } catch (e) {
            if (isFirstLoad) bar.classList.add("hidden");
        }
    }

    load(true);
    setInterval(function () {
        // Nothing to update while the tab is in the background, and refreshing there would
        // burn the shared CoinGecko rate limit for no visible benefit.
        if (document.hidden) return;
        load(false);
    }, REFRESH_MS);
})();
