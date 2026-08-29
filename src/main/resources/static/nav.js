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

    tabs.forEach(function (tab) {
        tab.addEventListener("click", function () {
            var target = tab.dataset.view;
            if (tab.classList.contains("active")) return;

            tabs.forEach(function (t) {
                var active = t === tab;
                t.classList.toggle("active", active);
                t.setAttribute("aria-selected", active ? "true" : "false");
            });
            Object.keys(views).forEach(function (key) {
                views[key].classList.toggle("hidden", key !== target);
            });

            if (onFirstShow[target]) onFirstShow[target]();
        });
    });

    window.CandlePill.attach(document.querySelector(".nav-tabs"), ".nav-tab");
})();
