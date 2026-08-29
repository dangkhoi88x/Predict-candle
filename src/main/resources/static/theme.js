/**
 * Light/dark switch. The theme is applied by an inline script in <head> so the page never
 * paints the wrong palette first; this file only owns the toggle button and persistence.
 *
 * Everything visual — including the SVG charts, treemap and pattern illustrations — reads
 * the CSS custom properties in style.css, so flipping the data-theme attribute is enough.
 * Nothing needs to be re-rendered.
 */
(function () {
    "use strict";

    var STORAGE_KEY = "candles-theme";
    var button = document.getElementById("theme-toggle");
    if (!button) return;

    function current() {
        return document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
    }

    function paintButton() {
        // The icon shows the theme you'd switch *to*, which is what people expect from a
        // single-button toggle.
        var isLight = current() === "light";
        button.textContent = isLight ? "🌙" : "☀️";
        button.setAttribute("aria-pressed", isLight ? "true" : "false");
    }

    function apply(theme) {
        document.documentElement.setAttribute("data-theme", theme);
        try {
            localStorage.setItem(STORAGE_KEY, theme);
        } catch (e) {
            // Storage blocked — the theme still applies for this page view.
        }
        paintButton();
    }

    button.addEventListener("click", function () {
        apply(current() === "light" ? "dark" : "light");
    });

    // Follow the OS setting as long as the user hasn't picked a theme themselves.
    if (window.matchMedia) {
        var mql = window.matchMedia("(prefers-color-scheme: light)");
        var onSystemChange = function (e) {
            var chosen = null;
            try {
                chosen = localStorage.getItem(STORAGE_KEY);
            } catch (err) {
                // Treat unreadable storage as "no explicit choice".
            }
            if (chosen) return;
            document.documentElement.setAttribute("data-theme", e.matches ? "light" : "dark");
            paintButton();
        };
        if (mql.addEventListener) mql.addEventListener("change", onSystemChange);
        else if (mql.addListener) mql.addListener(onSystemChange);
    }

    paintButton();
})();
