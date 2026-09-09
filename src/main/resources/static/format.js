(function () {
    "use strict";

    /* The number formats this page agrees on.
     *
     * Every one of these was previously a private copy in each module that needed it — nine
     * files, sixteen definitions — and they had already drifted. The two halves of the same
     * Heatmap tab formatted a price differently: the crypto side sized the decimals to the
     * magnitude, the S&P side always showed two. Nothing was wrong with either rule; what was
     * wrong is that one tab answered "what does this cost" in two voices.
     *
     * Two money formats, and the difference is the point:
     *
     *   price()  sizes decimals to magnitude — $79,260 · $104.72 · $0.089058
     *   usd()    always shows cents          — $79,260.00 · $104.72
     *
     * A market list is scanned down a column, so a price there wants to be as short as it can
     * be while staying exact enough to tell two coins apart; that is price(). A single figure
     * somebody is about to act on — the live round's price, a fill — wants its cents, because
     * a number that silently drops them reads as a rounder number than it is.
     *
     * Deliberately not here: the chart axis formatter in app.js, which is about fitting a
     * label into a tick and belongs with the renderer; the admin page's own set, which lives
     * on a separate page with its own palette and its own vi-VN conventions; and profile.js's
     * pct(correct, total), whose guard is about dividing by zero rather than about formatting.
     */

    /* Non-numbers render as an en dash rather than "NaN" or "$undefined". Every caller can hit
       this: a feed omits a field, a coin has no 24h change, a price has not arrived yet. */
    var MISSING = "–";

    function isNum(v) {
        return typeof v === "number" && isFinite(v);
    }

    /**
     * Price sized to its magnitude. Above a thousand the cents are noise; below a dollar they
     * are the only thing distinguishing two coins, so it goes the other way and shows more.
     */
    function price(v) {
        if (!isNum(v)) return MISSING;
        if (v >= 1000) return "$" + v.toLocaleString("en-US", { maximumFractionDigits: 0 });
        if (v >= 1) return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        return "$" + v.toLocaleString("en-US", { minimumFractionDigits: 4, maximumFractionDigits: 6 });
    }

    /**
     * Money with cents, always. For a single figure being acted on rather than scanned.
     *
     * The sign goes outside the symbol — -$1,234.56, not $-1,234.56 — because a loss is a
     * negative amount of money rather than an amount of negative money, and every ledger
     * anyone has read writes it that way. The trade terminal's own copy of this got that
     * right and the other copies never had to care, since a price cannot be negative.
     */
    function usd(v) {
        /* null before Number(), because Number(null) is 0 and a missing price would render as
           $0.00 — a figure that looks like an answer. Both copies this replaced guarded first
           for exactly that reason. */
        if (v === null || v === undefined) return MISSING;
        var n = Number(v);
        if (!isNum(n)) return MISSING;
        return (n < 0 ? "-$" : "$") + Math.abs(n).toLocaleString("en-US",
            { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    /** Market cap and turnover: $1.57T, $825.87M. Nobody reads those to the dollar. */
    function compactUsd(v) {
        if (!isNum(v)) return MISSING;
        return "$" + new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 2 }).format(v);
    }

    /** A count, grouped the Vietnamese way: 4.210 rather than 4,210. */
    function count(v) {
        return Number(v || 0).toLocaleString("vi-VN");
    }

    /** A ratio as whole percent. Null is a rate nobody has enough data for, not zero. */
    function percent(fraction) {
        if (fraction === null || fraction === undefined) return MISSING;
        return Math.round(fraction * 100) + "%";
    }

    /** A change, carrying its sign: +1.42%, -0.87%. */
    function signedPct(v) {
        if (v === null || v === undefined) return MISSING;
        var n = Number(v);
        return (n >= 0 ? "+" : "") + n.toFixed(2) + "%";
    }

    /**
     * A countdown, zero-padded on both halves.
     *
     * Padded because these sit in tabular-nums text that must not change width as the clock
     * runs down, and because the topbar banner and the live tab show the same deadline a few
     * hundred pixels apart — one of them saying 9:58 while the other says 09:58 is two clocks
     * that look like they disagree.
     */
    function clock(ms) {
        var total = Math.max(0, Math.ceil(ms / 1000));
        var m = Math.floor(total / 60);
        var s = total % 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    window.CandleFormat = {
        price: price,
        usd: usd,
        compactUsd: compactUsd,
        count: count,
        percent: percent,
        signedPct: signedPct,
        clock: clock,
    };
})();
