/**
 * Sidebar navigation: which of the seven panes is on screen.
 *
 * The switch is an attribute on the container, never `.hidden` on a section. Each
 * admin-*.js module already owns `.hidden` on its own section and re-asserts it every time
 * it hears `candles:admin` — so a nav that reached for the same class would lose the pane
 * again the moment a module refreshed. CSS ANDs the two: a section shows when the container
 * selects its pane *and* its module has not hidden it.
 *
 * Loads before the modules so `CandleAdminNav.go` exists by the time one of them calls it.
 */
(function () {
    "use strict";

    var PANES = ["overview", "ops", "blog", "content", "media", "assets", "players"];

    var panes = document.getElementById("admin-panes");
    var crumb = document.getElementById("adm-crumb-pane");
    var items = Array.prototype.slice.call(document.querySelectorAll("[data-pane-link]"));
    if (!panes) return;

    function title(pane) {
        var item = items.find(function (b) { return b.dataset.paneLink === pane; });
        return item ? item.getAttribute("title") : "Tổng quan";
    }

    function go(pane, options) {
        if (PANES.indexOf(pane) === -1) pane = "overview";
        panes.dataset.pane = pane;
        items.forEach(function (item) {
            var on = item.dataset.paneLink === pane;
            if (on) item.setAttribute("aria-current", "page");
            else item.removeAttribute("aria-current");
        });
        if (crumb) crumb.textContent = title(pane);

        /* replaceState, not pushState: the panes are one screen with a bookmarkable name,
           not seven history entries for Back to walk through. */
        if (!options || options.hash !== false) {
            try {
                history.replaceState(null, "", "#" + pane);
            } catch (e) {
                // A file:// origin refuses this; the pane still switched.
            }
        }
        document.dispatchEvent(new CustomEvent("candles:pane", { detail: { pane: pane } }));
    }

    items.forEach(function (item) {
        item.addEventListener("click", function () { go(item.dataset.paneLink); });
    });

    window.addEventListener("hashchange", function () {
        go((location.hash || "").replace(/^#/, ""), { hash: false });
    });

    go((location.hash || "").replace(/^#/, ""), { hash: !!location.hash });

    window.CandleAdminNav = {
        go: go,
        current: function () { return panes.dataset.pane; },
    };
})();
