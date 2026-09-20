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
         * The one place these tabs say "this did not load", so they say it the same way. It
         * replaces whatever the section held rather than appending to it.
         *
         * <b>With a retry, the message stops being a dead end.</b> These tabs used to end at
         * "thử tải lại trang" — asking someone to throw away the round they were playing, the
         * scroll they were at and every other tab's state to re-ask one request that had
         * probably already come back. The button asks again in place.
         *
         * The caller supplies it, because only the caller knows what has to be forgotten first:
         * a tab that builds once has a flag saying it already did, and a retry that does not
         * clear it redraws the same emptiness. Each caller's retry runs its own build, so a
         * second failure draws this notice again, button and all.
         */
        notice: function (target, text, retry) {
            if (!target) return;
            target.innerHTML = "";
            var p = document.createElement("p");
            p.className = "view-notice";
            p.textContent = text;
            target.appendChild(p);
            if (typeof retry !== "function") return;

            var button = document.createElement("button");
            button.type = "button";
            button.className = "ghost-btn view-notice-retry";
            button.textContent = "Thử lại";
            button.addEventListener("click", function () {
                /* Disabled while the request is out: the failure people hit is a slow or dropped
                   connection, and a button that still looks pressable invites a queue of
                   identical requests against a server that is already struggling. */
                button.disabled = true;
                button.textContent = "Đang tải…";
                Promise.resolve()
                    .then(retry)
                    .catch(function () { /* the build draws this notice again */ });
            });
            p.appendChild(document.createElement("br"));
            p.appendChild(button);
        },
    };
})();
