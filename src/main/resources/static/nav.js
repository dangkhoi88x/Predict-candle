(function () {
    "use strict";

    /* Three sets of controls point at the same eleven views: the rail (the tablist, and the
       only place every view is listed), the phone's bottom bar (the first four, plus a key
       that opens the rail as a sheet), and deep links scattered through the views themselves
       — a "Chơi ngay" on the game tab, "Xem tất cả" under the weekly board. So activation is
       keyed on the view name rather than on the element that was pressed; a control is
       whatever carries data-view, and nothing has to know what else points where. */
    var railItems = Array.prototype.slice.call(document.querySelectorAll(".rail-item"));
    var barItems = Array.prototype.slice.call(document.querySelectorAll(".tabbar-item[data-view]"));
    var railGroups = Array.prototype.slice.call(document.querySelectorAll(".rail-group"));
    var rail = document.getElementById("rail");
    var railBackdrop = document.getElementById("rail-backdrop");
    var moreBtn = document.getElementById("tabbar-more");

    var views = {
        game: document.getElementById("view-game"),
        daily: document.getElementById("view-daily"),
        live: document.getElementById("view-live"),
        trade: document.getElementById("view-trade"),
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
        patterns: function () { window.__initPatternsView && window.__initPatternsView(); },
        technical: function () { window.__initTechnicalView && window.__initTechnicalView(); },
        psychology: function () { window.__initPsychologyView && window.__initPsychologyView(); },
    };

    /* The profile reloads every time it is opened, not just the first time — a round played
       on the game tab moves the numbers it shows. */
    var onEveryShow = {
        /* Re-read on every reveal rather than once: signing in mid-visit turns an anonymous
           attempt into an account with a streak and a recorded result, and the tab has to be
           able to catch up with that. */
        daily: function () {
            window.__initDailyView && window.__initDailyView();
            // Its own module on the same tab: separate data, separate record, separate failure.
            window.__initPatternQuizView && window.__initPatternQuizView();
        },
        /* Prices move and a trade made on this tab changes every number on it, so it re-reads
           on every reveal rather than once. */
        trade: function () { window.__initTradeView && window.__initTradeView(); },
        profile: function () { window.__initProfileView && window.__initProfileView(); },
        /* Rebuilt on every reveal, not just the first: ranks move while you play, and a board
           showing where you stood when the page loaded is the one thing it must not do. The
           server caches for a minute, so reopening the tab costs a request and nothing more. */
        leaderboard: function () { window.__initLeaderboardView && window.__initLeaderboardView(); },
    };

    /* Roving tabindex: the whole tablist is a single stop in the page's tab order and the
       arrow keys move within it. That is the contract role="tab" advertises, and leaving it
       out is worse than never claiming the role — a screen reader announces "tab, 1 of 11"
       and the keys it tells the user to press do nothing.

       The stop is normally the selected tab. It is not when a player has closed the group
       they are standing in: the selected tab is then not rendered, so it cannot be tabbed to
       and the tablist would have no way in at all. In that one case the first tab still on
       screen holds the stop. */
    function syncTabStop() {
        var ring = visibleRailItems();
        var selectedIsVisible = ring.some(function (item) { return item.classList.contains("active"); });
        railItems.forEach(function (item) {
            item.tabIndex = -1;
        });
        ring.forEach(function (item, index) {
            if (item.classList.contains("active") || (!selectedIsVisible && index === 0)) item.tabIndex = 0;
        });
    }

    function current() {
        for (var i = 0; i < railItems.length; i++) {
            if (railItems[i].classList.contains("active")) return railItems[i].dataset.view;
        }
        return null;
    }

    /* "MỚI" beside Thử Thách is news only until somebody has opened it; after that it is a label
       that never changes, which is how a badge stops being read. Remembered per browser. */
    var NEW_DAILY_KEY = "candles-seen-daily";
    var newDailyTag = document.getElementById("rail-tag-new-daily");

    function markDailySeen() {
        if (!newDailyTag) return;
        newDailyTag.classList.add("hidden");
        try {
            localStorage.setItem(NEW_DAILY_KEY, "1");
        } catch (e) {
            // Storage blocked: the tag comes back next visit, which is harmless.
        }
    }

    try {
        if (newDailyTag && localStorage.getItem(NEW_DAILY_KEY)) newDailyTag.classList.add("hidden");
    } catch (e) {
        // Storage blocked: leave the tag as shipped.
    }

    /* ---- view bundles ----------------------------------------------------------------
       Each view whose scripts carry data-view in index.html has its own bundle, listed in
       window.CandleChunks by AppShellService. It is fetched the first time that view opens, so
       a visitor who plays the game never downloads the trade terminal or the blog.

       One promise per bundle, kept whatever happens next: several rapid switches, or a rail
       click while a tab is already loading, must not start a second download of the same file.
       A failure clears the entry instead, so opening the tab again is a real retry rather than
       a permanently empty pane. */
    var chunkLoads = {};
    var chunkReady = {};

    function loadChunk(view) {
        var src = (window.CandleChunks || {})[view];
        if (!src) return Promise.resolve();
        if (chunkLoads[view]) return chunkLoads[view];

        chunkLoads[view] = new Promise(function (resolve, reject) {
            var script = document.createElement("script");
            script.src = src;
            script.onload = function () { chunkReady[view] = true; resolve(); };
            script.onerror = function () { reject(new Error("Không tải được " + src)); };
            document.head.appendChild(script);
        }).catch(function (e) {
            delete chunkLoads[view];
            console.error(e);
            throw e;
        });
        return chunkLoads[view];
    }

    function runBuilders(target) {
        if (onFirstShow[target]) onFirstShow[target]();
        if (onEveryShow[target]) onEveryShow[target]();
    }

    /* The builders live in the bundle, so they cannot be called until it has arrived — but only
       then. **With the bundle already in hand they run synchronously**, inside activate(), which
       is where they ran before this existed: technical-patterns.js opens a card by clicking its
       tab and then reaching for the card, and a builder deferred to a microtask leaves it
       reaching into a view that has not been built yet. That is a ?card= link opening the right
       tab and nothing else, with no error anywhere. */
    function build(target) {
        var pending = (window.CandleChunks || {})[target] && !chunkReady[target];
        if (!pending) {
            runBuilders(target);
            return;
        }
        loadChunk(target).then(function () {
            runBuilders(target);
        }, function () {
            /* Reported by loadChunk. The tab stays empty and opening it again retries. */
        });
    }

    function activate(target) {
        if (target === "daily") markDailySeen();
        if (!views[target] || target === current()) {
            closeRail();
            return;
        }

        /* Before the selection moves: a tab inside a closed group would leave the tablist
           with no visible selected stop and the roving tabindex with nowhere to put its 0.
           Deep links reach the blog and the pattern libraries from inside the views, so this
           is the ordinary path, not an edge case. */
        openGroupOf(target);

        railItems.forEach(function (item) {
            var active = item.dataset.view === target;
            item.classList.toggle("active", active);
            item.setAttribute("aria-selected", active ? "true" : "false");
        });
        syncTabStop();
        /* The bottom bar lists four of the eleven, so opening any of the other seven leaves
           it with nothing marked — which is right: none of its keys is where you are. */
        barItems.forEach(function (item) {
            item.classList.toggle("active", item.dataset.view === target);
        });
        Object.keys(views).forEach(function (key) {
            views[key].classList.toggle("hidden", key !== target);
        });

        closeRail();

        build(target);

        /* Mirrors `candles:pane` on the admin page. The game tab needs to know when it has
           gone off screen — a round left running behind the blog tab keeps timing out and
           recording misses against a player who is reading, not playing. */
        document.dispatchEvent(new CustomEvent("candles:view", { detail: { view: target } }));
    }

    /* ---- disclosure groups ----------------------------------------------------------- */

    /* Grouping the eleven views did not make the rail short — they are still eleven rows, and
       four of them are reference pages somebody opens twice a year sitting at the same weight
       as the game. So a group closes, and Học ships closed.

       The state is remembered per group rather than reset each visit: a player who opened Học
       to read a pattern page did not ask to be shown it again tomorrow, and one who never
       opens it did not ask to be shown it at all. A group missing from storage keeps whatever
       the markup shipped, so adding a group later does not need a migration. */
    var GROUP_KEY = "candles-rail-groups";

    function readGroupState() {
        try {
            return JSON.parse(localStorage.getItem(GROUP_KEY)) || {};
        } catch (e) {
            return {}; // storage blocked, or something else wrote nonsense under the key
        }
    }

    function writeGroupState() {
        var state = {};
        railGroups.forEach(function (group) {
            state[groupId(group)] = group.dataset.open === "true";
        });
        try {
            localStorage.setItem(GROUP_KEY, JSON.stringify(state));
        } catch (e) {
            // Unsaved, so it lasts the visit — better than refusing to open the group.
        }
    }

    function groupId(group) {
        return group.querySelector(".rail-group-items").id;
    }

    function setGroupOpen(group, open) {
        group.dataset.open = open ? "true" : "false";
        group.querySelector(".rail-group-toggle").setAttribute("aria-expanded", open ? "true" : "false");
        paintGroupFlag(group);
        syncTabStop();
    }

    function openGroupOf(view) {
        var item = null;
        for (var i = 0; i < railItems.length; i++) {
            if (railItems[i].dataset.view === view) item = railItems[i];
        }
        if (!item) return;
        var group = item.closest(".rail-group");
        if (group && group.dataset.open !== "true") {
            setGroupOpen(group, true);
            writeGroupState();
        }
    }

    /* A closed group must not swallow a signal. The live countdown and the caller's rank are
       drawn on items, so a shut group shows a dot in their place — otherwise collapsing
       "Bạn & cộng đồng" quietly hides the rank it was collapsed to make room for. The view
       currently on screen counts as a signal too: closing the group you are standing in is
       allowed, and the dot is then the only thing left saying where you are. */
    function paintGroupFlag(group) {
        var closed = group.dataset.open !== "true";
        var carries = group.querySelector(".rail-tag:not(.hidden)") || group.querySelector(".rail-item.active");
        group.querySelector(".rail-group-flag").classList.toggle("hidden", !(closed && carries));
    }

    var storedGroups = readGroupState();
    railGroups.forEach(function (group) {
        var stored = storedGroups[groupId(group)];
        setGroupOpen(group, typeof stored === "boolean" ? stored : group.dataset.open === "true");

        group.querySelector(".rail-group-toggle").addEventListener("click", function () {
            setGroupOpen(group, group.dataset.open !== "true");
            writeGroupState();
        });

        /* The tags are written by other modules on their own schedule — live-banner.js ticks
           its countdown every second, play-sidebar.js publishes a rank once a board lands.
           Watching the class is how CandlePill already stays in step with callers that know
           nothing about it, and it means neither module has to learn the rail exists. */
        var tags = group.querySelectorAll(".rail-tag");
        if (tags.length && window.MutationObserver) {
            var observer = new MutationObserver(function () { paintGroupFlag(group); });
            Array.prototype.forEach.call(tags, function (tag) {
                observer.observe(tag, { attributes: true, attributeFilter: ["class"] });
            });
        }
    });

    /* ---- the rail as a phone sheet -------------------------------------------------- */

    function openRail() {
        rail.classList.add("is-open");
        railBackdrop.hidden = false;
        moreBtn.setAttribute("aria-expanded", "true");
    }

    function closeRail() {
        if (!rail.classList.contains("is-open")) return;
        rail.classList.remove("is-open");
        railBackdrop.hidden = true;
        moreBtn.setAttribute("aria-expanded", "false");
    }

    moreBtn.addEventListener("click", function () {
        if (rail.classList.contains("is-open")) closeRail();
        else openRail();
    });
    railBackdrop.addEventListener("click", closeRail);
    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape") closeRail();
    });

    /* ---- wiring --------------------------------------------------------------------- */

    /* Visible rail items only: the arrow keys walk what is on screen, so the profile tab
       must drop out of the ring while signed out rather than being a stop that focuses
       nothing, and so must the four inside a closed group. Recomputed per press because that
       list changes on sign-in and on every disclosure. */
    function visibleRailItems() {
        return railItems.filter(function (item) {
            if (item.classList.contains("hidden")) return false;
            var group = item.closest(".rail-group");
            return !group || group.dataset.open === "true";
        });
    }

    syncTabStop();

    railItems.forEach(function (item) {
        item.addEventListener("click", function () { activate(item.dataset.view); });

        item.addEventListener("keydown", function (event) {
            var ring = visibleRailItems();
            var index = ring.indexOf(item);
            var next = null;
            /* Vertical list, so Down/Up are the keys the role advertises. Left/Right are
               honoured too: the strip this replaced answered to them for a year. */
            if (event.key === "ArrowDown" || event.key === "ArrowRight") next = ring[(index + 1) % ring.length];
            else if (event.key === "ArrowUp" || event.key === "ArrowLeft") next = ring[(index - 1 + ring.length) % ring.length];
            else if (event.key === "Home") next = ring[0];
            else if (event.key === "End") next = ring[ring.length - 1];
            if (!next) return;

            event.preventDefault();
            activate(next.dataset.view);
            next.focus();
        });
    });

    barItems.forEach(function (item) {
        item.addEventListener("click", function () { activate(item.dataset.view); });
    });

    /* Deep links inside the views — the topbar's live banner, "Chơi ngay" on the game tab's
       challenge card, "Xem tất cả" under the weekly board. Delegated so a view built after
       load gets the behaviour without registering anything. */
    document.addEventListener("click", function (event) {
        var link = event.target.closest ? event.target.closest("[data-nav-view]") : null;
        if (!link) return;
        event.preventDefault();
        activate(link.dataset.navView);
    });

    /* The rank beside "Bảng Xếp Hạng". play-sidebar.js publishes it off the board it already
       fetches; the rail only draws. No rank — signed out, or short of the minimum guesses —
       means no tag at all, because an empty pill beside a label reads as something that
       failed to load. */
    var rankTag = document.getElementById("rail-tag-rank");

    document.addEventListener("candles:rank", function (event) {
        var rank = event.detail.rank;
        rankTag.classList.toggle("hidden", !rank);
        if (rank) rankTag.textContent = "#" + rank;
    });

    /* The profile tab only exists for a signed-in player. */
    var profileTab = document.getElementById("tab-profile");

    document.addEventListener("candles:session", function (event) {
        var signedIn = !!event.detail.user;
        profileTab.classList.toggle("hidden", !signedIn);
        if (!signedIn && profileTab.classList.contains("active")) {
            activate("game"); // signed out while looking at it — nothing left to show
        }
    });

    /* ?view=daily opens on that view — the installed app's shortcuts use it, and so can any link.
       Removed from the address bar afterwards, so reloading or sharing the page does not pin
       whoever opens it to a tab. The profile is refused: it only exists signed in, and a link
       cannot know that. */
    /* A link that addresses a view's content — a challenge, a post, a library card — is read by
       a module that now lives in that view's bundle. Nothing would read it if the bundle only
       arrived when somebody opened the tab by hand, so the parameter loads it here. */
    var PARAM_CHUNKS = { thach: "daily", post: "blog", card: "technical" };

    function loadChunksForLink(params) {
        Object.keys(PARAM_CHUNKS).forEach(function (param) {
            if (params.has(param)) loadChunk(PARAM_CHUNKS[param]);
        });
    }

    function openRequestedView() {
        try {
            var params = new URLSearchParams(window.location.search);
            loadChunksForLink(params);
            var wanted = params.get("view");
            if (wanted && views[wanted] && wanted !== "profile") activate(wanted);
            if (params.has("view") || params.has("source")) {
                params.delete("view");
                params.delete("source");
                var rest = params.toString();
                history.replaceState(null, "", window.location.pathname + (rest ? "?" + rest : "") + window.location.hash);
            }
        } catch (e) {
            // An old browser without URLSearchParams opens on the default view, which is fine.
        }
    }
    /* After every script has run, not now: most views' builders (heatmap, patterns, leaderboard,
       profile…) are defined by files loaded after this one, and activating before them would open
       an empty tab that never builds. The scripts sit at the end of <body>, so DOMContentLoaded
       fires once they have all executed. */
    document.addEventListener("DOMContentLoaded", openRequestedView);

    /* Once the page is quiet, fetch the rest so the first tab switch costs nothing. Prefetch
       rather than a script tag: the browser stores them and runs nothing, so a bundle nobody
       opens never costs parse or execution time. */
    function prefetchChunksWhenIdle() {
        var chunks = window.CandleChunks || {};
        Object.keys(chunks).forEach(function (view) {
            if (chunkLoads[view]) return;
            var link = document.createElement("link");
            link.rel = "prefetch";
            link.as = "script";
            link.href = chunks[view];
            document.head.appendChild(link);
        });
    }

    if (window.requestIdleCallback) {
        window.addEventListener("load", function () {
            window.requestIdleCallback(prefetchChunksWhenIdle, { timeout: 5000 });
        });
    } else {
        window.addEventListener("load", function () { setTimeout(prefetchChunksWhenIdle, 2000); });
    }

    window.CandleNav = {
        go: activate,

        /**
         * Runs fn once the document is parsed — immediately when that has already happened,
         * which is the case for every module inside a view bundle: those arrive long after
         * DOMContentLoaded, and a listener added then would never fire.
         */
        ready: function (fn) {
            if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn);
            else fn();
        },

        /** Loads a view's bundle without opening it. */
        load: loadChunk
    };
})();
