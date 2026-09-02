/**
 * Fetches an editable content set.
 *
 * The three tabs that use this — candlestick patterns, chart patterns, psychology — used to
 * pass the array they were compiled from as a fallback, so a failed request served stale
 * content instead of an empty tab. That array is gone: the database has been the source of
 * truth through a real deploy, and keeping a second copy in the page meant every edit in the
 * admin silently disagreed with what a reader saw whenever the API hiccuped.
 *
 * What replaces it is honesty rather than nothing. A failure throws, and the caller draws a
 * notice saying the section could not load — which is a truthful empty state, where stale
 * content pretending to be current is not.
 *
 * The API wraps each entry in a row (key, position, published…) whose `body` is the entry
 * itself, unchanged. Unwrapping that here is what lets the renderers stay exactly as they
 * were written against the hard-coded arrays.
 */
(function () {
    "use strict";

    window.CandleContent = {
        /** Resolves to the entries, or throws — there is no third answer any more. */
        load: async function (kind) {
            var res = await fetch("/api/content/" + kind);
            if (!res.ok) throw new Error("Máy chủ trả về " + res.status);
            var rows = await res.json();
            return rows.map(function (row) { return row.body; });
        },

        /**
         * The one place these four tabs say "this did not load", so they say it the same way.
         * Text only, and it replaces whatever the grid held rather than appending to it.
         */
        notice: function (target, text) {
            if (!target) return;
            target.innerHTML = "";
            var p = document.createElement("p");
            p.className = "view-notice";
            p.textContent = text;
            target.appendChild(p);
        },
    };
})();
