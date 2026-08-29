(function () {
    "use strict";

    var tabs = Array.prototype.slice.call(document.querySelectorAll(".nav-tab"));
    var views = {
        game: document.getElementById("view-game"),
        heatmap: document.getElementById("view-heatmap"),
        patterns: document.getElementById("view-patterns"),
        technical: document.getElementById("view-technical"),
        psychology: document.getElementById("view-psychology"),
        blog: document.getElementById("view-blog"),
    };
    var onFirstShow = {
        heatmap: function () { window.__initHeatmapView && window.__initHeatmapView(); },
        blog: function () { window.__initBlogView && window.__initBlogView(); },
    };

    function activate(tab) {
        var target = tab.dataset.view;
        if (tab.classList.contains("active")) return;

        tabs.forEach(function (t) {
            var active = t === tab;
            t.classList.toggle("active", active);
            t.setAttribute("aria-selected", active ? "true" : "false");
            /* Roving tabindex: the whole tablist is a single stop in the page's tab order
               and the arrow keys move within it. That is the contract role="tab" advertises,
               and leaving it out is worse than never claiming the role — a screen reader
               announces "tab, 1 of 6" and the keys it tells the user to press do nothing. */
            t.tabIndex = active ? 0 : -1;
        });
        Object.keys(views).forEach(function (key) {
            views[key].classList.toggle("hidden", key !== target);
        });

        if (onFirstShow[target]) onFirstShow[target]();
    }

    tabs.forEach(function (tab, index) {
        tab.tabIndex = tab.classList.contains("active") ? 0 : -1;
        tab.addEventListener("click", function () { activate(tab); });

        tab.addEventListener("keydown", function (event) {
            var next = null;
            if (event.key === "ArrowRight") next = tabs[(index + 1) % tabs.length];
            else if (event.key === "ArrowLeft") next = tabs[(index - 1 + tabs.length) % tabs.length];
            else if (event.key === "Home") next = tabs[0];
            else if (event.key === "End") next = tabs[tabs.length - 1];
            if (!next) return;

            event.preventDefault();
            activate(next);
            next.focus();
        });
    });

    window.CandlePill.attach(document.querySelector(".nav-tabs"), ".nav-tab");
})();
