/**
 * Fetches an editable content set, falling back to the copy compiled into the page.
 *
 * The three tabs that use this — patterns, chart patterns, psychology — each keep the array
 * they were built from. It is the same data the database was seeded with, so serving it when
 * a request fails costs nothing but freshness, and beats an empty tab.
 *
 * The API wraps each entry in a row (key, position, published…) whose `body` is the entry
 * itself, unchanged. Unwrapping that here is what lets the renderers stay exactly as they
 * were written against the hard-coded arrays.
 */
(function () {
    "use strict";

    window.CandleContent = {
        load: async function (kind, fallback) {
            try {
                var res = await fetch("/api/content/" + kind);
                if (!res.ok) return fallback;
                var rows = await res.json();
                // An empty set means a migration has not run, not that an editor deleted
                // thirteen candlestick patterns.
                return rows.length ? rows.map(function (row) { return row.body; }) : fallback;
            } catch (e) {
                return fallback;
            }
        },
    };
})();
