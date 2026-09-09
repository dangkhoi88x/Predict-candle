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

    // The admin page draws the toggle with two inline Lucide icons instead of an emoji, so
    // writing textContent there would erase them. Whichever markup the page supplies, the
    // rule below is the same one.
    var moon = button.querySelector('[data-theme-icon="moon"]');
    var sun = button.querySelector('[data-theme-icon="sun"]');

    /* The two <meta name="theme-color"> tags carry media queries, which follow the OS rather
       than the choice stored here — so a light theme picked on a dark machine would leave the
       mobile browser's chrome dark around a light page. Writing the active colour into both
       makes whichever one matches the right one.
       
       The colours are read off the tags instead of being listed here, because the two pages
       do not share a background: the game sits on --bg and the admin dashboard on --adm-bg.
       Each page declares its own pair and this stays ignorant of which one it is running on. */
    var themeColors = (function () {
        var found = {};
        var metas = document.querySelectorAll('meta[name="theme-color"][media]');
        for (var i = 0; i < metas.length; i++) {
            var key = metas[i].getAttribute("media").indexOf("dark") !== -1 ? "dark" : "light";
            found[key] = metas[i].getAttribute("content");
        }
        return found;
    })();

    function paintThemeColor() {
        var colour = themeColors[current()];
        if (!colour) return;
        var metas = document.querySelectorAll('meta[name="theme-color"]');
        for (var i = 0; i < metas.length; i++) metas[i].setAttribute("content", colour);
    }

    function paintButton() {
        // The icon shows the theme you'd switch *to*, which is what people expect from a
        // single-button toggle.
        var isLight = current() === "light";
        if (moon && sun) {
            moon.classList.toggle("hidden", !isLight);
            sun.classList.toggle("hidden", isLight);
        } else {
            button.textContent = isLight ? "🌙" : "☀️";
        }
        button.setAttribute("aria-pressed", isLight ? "true" : "false");
        paintThemeColor();
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
