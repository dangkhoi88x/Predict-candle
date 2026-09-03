(function () {
    "use strict";

    var tabs = Array.prototype.slice.call(document.querySelectorAll(".nav-tab"));
    var views = {
        game: document.getElementById("view-game"),
        live: document.getElementById("view-live"),
        heatmap: document.getElementById("view-heatmap"),
        patterns: document.getElementById("view-patterns"),
        technical: document.getElementById("view-technical"),
        psychology: document.getElementById("view-psychology"),
        blog: document.getElementById("view-blog"),
        leaderboard: document.getElementById("view-leaderboard"),
        profile: document.getElementById("view-profile"),
    };
    var onFirstShow = {
        live: function () { window.__initLiveView && window.__initLiveView(); },
        heatmap: function () { window.__initHeatmapView && window.__initHeatmapView(); },
        blog: function () { window.__initBlogView && window.__initBlogView(); },
    };

    /* The profile reloads every time it is opened, not just the first time — a round played
       on the game tab moves the numbers it shows. */
    var onEveryShow = {
        profile: function () { window.__initProfileView && window.__initProfileView(); },
        /* Rebuilt on every reveal, not just the first: ranks move while you play, and a board
           showing where you stood when the page loaded is the one thing it must not do. The
           server caches for a minute, so reopening the tab costs a request and nothing more. */
        leaderboard: function () { window.__initLeaderboardView && window.__initLeaderboardView(); },
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
        if (onEveryShow[target]) onEveryShow[target]();

        /* Mirrors `candles:pane` on the admin page. The game tab needs to know when it has
           gone off screen — a round left running behind the blog tab keeps timing out and
           recording misses against a player who is reading, not playing. */
        document.dispatchEvent(new CustomEvent("candles:view", { detail: { view: target } }));
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

    /* The profile tab only exists for a signed-in player. Hiding it changes the tab strip's
       width, which the pill's own ResizeObserver picks up, so the indicator re-measures
       without nav.js having to tell it anything. */
    var profileTab = document.getElementById("tab-profile");

    document.addEventListener("candles:session", function (event) {
        var signedIn = !!event.detail.user;
        profileTab.classList.toggle("hidden", !signedIn);
        if (!signedIn && profileTab.classList.contains("active")) {
            activate(tabs[0]); // signed out while looking at it — nothing left to show
        }
    });

    window.CandlePill.attach(document.querySelector(".nav-tabs"), ".nav-tab");
})();
