/* One sliding-pill control, shared by the nav and every asset picker.
 *
 * Callers keep owning their own click handling; this only watches for the "active" class
 * moving between options and slides the indicator to follow. That way a picker can also
 * change selection programmatically and the pill still tracks it. */
window.CandlePill = (function () {
    "use strict";

    function prefersReducedMotion() {
        return !!(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
    }

    function attach(track, optionSelector) {
        if (!track) return null;

        var options = Array.prototype.slice.call(track.querySelectorAll(optionSelector));
        if (!options.length) return null;

        var indicator = document.createElement("span");
        indicator.className = "pill-indicator no-transition";
        indicator.setAttribute("aria-hidden", "true");
        track.insertBefore(indicator, track.firstChild);
        track.classList.add("has-indicator");

        var current = null;

        function activeOption() {
            for (var i = 0; i < options.length; i++) {
                if (options[i].classList.contains("active")) return options[i];
            }
            return null;
        }

        // A single transform for both axes keeps this on the compositor; only width lays out.
        function place(option, animate) {
            if (!option) return;
            var rect = option.getBoundingClientRect();
            if (!rect.width) return; // track not laid out yet — a later pass will catch it

            indicator.classList.toggle("no-transition", !animate);
            // Position comes from the offset pair because it is relative to the track and
            // unaffected by how far the track has been scrolled. Size comes from the rect
            // because offsetWidth/offsetHeight round to whole pixels, which leaves the pill
            // overhanging the option by up to half a pixel on each edge.
            indicator.style.width = rect.width + "px";
            indicator.style.height = rect.height + "px";
            indicator.style.transform = "translate(" + option.offsetLeft + "px, " + option.offsetTop + "px)";

            if (!animate) {
                // Flush the untransitioned values before transitions are allowed back on,
                // otherwise the next move animates from wherever the element used to be.
                void indicator.offsetWidth;
                indicator.classList.remove("no-transition");
            }
        }

        /* When the options outgrow the track it scrolls, and a selection past either edge
           should be brought into view — by nudging this track's own scrollLeft, not via
           scrollIntoView, which would also move the page. */
        function reveal(option) {
            var margin = 8;
            var left = option.offsetLeft - margin;
            var right = option.offsetLeft + option.offsetWidth + margin;
            var viewLeft = track.scrollLeft;
            var viewRight = viewLeft + track.clientWidth;
            var target = null;

            if (left < viewLeft) target = left;
            else if (right > viewRight) target = right - track.clientWidth;
            if (target === null) return;

            target = Math.max(0, Math.min(target, track.scrollWidth - track.clientWidth));

            if (prefersReducedMotion() || !track.scrollTo) {
                track.scrollLeft = target;
                return;
            }

            var from = track.scrollLeft;
            track.scrollTo({ left: target, behavior: "smooth" });

            /* Smooth scrolling is a request, not a guarantee — it is a no-op in some
               embedded and automated views. Unchecked, that strands the selected option off
               screen with nothing in the track looking selected, so confirm it started and
               finish the job outright if it did not. */
            window.setTimeout(function () {
                if (track.scrollLeft === from) track.scrollLeft = target;
            }, 120);
        }

        function sync(animate) {
            var next = activeOption();
            if (!next || next === current) return;
            current = next;
            place(next, animate);
            if (animate) reveal(next);
        }

        function reposition() {
            place(current, false);
        }

        current = activeOption();
        place(current, false);

        /* Watching the class attribute rather than clicks means the pill never has to race
           the caller's own click handler to see which option won. */
        if (window.MutationObserver) {
            new MutationObserver(function () {
                sync(true);
            }).observe(track, { subtree: true, attributes: true, attributeFilter: ["class"] });
        }

        /* Option widths settle late: layouts reflow at breakpoints and the UI font can still
           be swapping. A ResizeObserver on the track catches both, plus anything else that
           reflows it. */
        if (window.ResizeObserver) {
            new ResizeObserver(reposition).observe(track);
        } else {
            window.addEventListener("resize", reposition);
        }
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(reposition);
        }

        return { sync: sync, reposition: reposition };
    }

    return { attach: attach };
})();
