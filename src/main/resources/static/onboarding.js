(function () {
    "use strict";

    /* Three steps shown to a first visit before the first chart is dealt.

       Before, not over: the game deals a round the moment the page loads and its 20-second
       clock starts from the server's token, so a card laid over a running round would be
       spending a newcomer's first guess while they read how to make one. The game waits on
       `gameReady()` instead, and only asks for a chart once this is out of the way and the
       game view is the one on screen.

       "First visit" is read from storage the page already writes rather than from a new flag
       alone: anyone with a browser tally, a theme choice or a rail layout on record has been
       here before this file existed, and greeting them as strangers would be the wrong first
       impression of a feature meant for strangers. */

    var SEEN_KEY = "candles-onboarded";
    var EARLIER_VISIT_KEYS = ["candleGuess.stats.v1", "candles-theme", "candles-rail-groups"];

    var modal = document.getElementById("onboarding");
    var card = modal.querySelector(".onboarding-card");
    var steps = Array.prototype.slice.call(modal.querySelectorAll(".onboarding-step"));
    var dots = Array.prototype.slice.call(modal.querySelectorAll(".onboarding-dot"));
    var backBtn = document.getElementById("onboarding-back");
    var nextBtn = document.getElementById("onboarding-next");
    var playBtn = document.getElementById("onboarding-play");
    var dailyBtn = document.getElementById("onboarding-daily");
    var skipBtn = document.getElementById("onboarding-skip");
    var helpBtn = document.getElementById("help-toggle");

    var index = 0;
    var returnFocus = null;
    var finishHooks = [];
    /* Where the tour sent the player the last time it closed — "game" when they pressed
       "Chơi thử ngay" or skipped it. The game deals its first chart straight away only then:
       that press *is* the player asking to start. */
    var closedTo = null;

    function read(key) {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function isFirstVisit() {
        if (read(SEEN_KEY)) return false;
        return !EARLIER_VISIT_KEYS.some(function (key) { return read(key) !== null; });
    }

    function markSeen() {
        try {
            localStorage.setItem(SEEN_KEY, "1");
        } catch (e) {
            // Storage blocked: the tour comes back next visit, which is the lesser problem.
        }
    }

    function show(step) {
        index = Math.max(0, Math.min(step, steps.length - 1));
        steps.forEach(function (el, i) { el.classList.toggle("hidden", i !== index); });
        dots.forEach(function (el, i) { el.classList.toggle("active", i === index); });
        var last = index === steps.length - 1;
        backBtn.classList.toggle("hidden", index === 0);
        nextBtn.classList.toggle("hidden", last);
        playBtn.classList.toggle("hidden", !last);
        dailyBtn.classList.toggle("hidden", !last);
        /* preventScroll, and the card back to its top: on a phone the card scrolls, and moving
           focus to a button at its foot would carry the view down past the step's title. */
        (last ? playBtn : nextBtn).focus({ preventScroll: true });
        card.scrollTop = 0;
    }

    function open() {
        returnFocus = document.activeElement;
        modal.classList.remove("hidden");
        document.body.classList.add("onboarding-open");
        show(0);
    }

    /** @param destination "game" or "daily"; @param how the analytics step name, if any */
    function close(destination, how) {
        if (modal.classList.contains("hidden")) return;
        if (how && window.CandleAnalytics) window.CandleAnalytics.track(how);
        modal.classList.add("hidden");
        document.body.classList.remove("onboarding-open");
        markSeen();
        closedTo = destination;
        if (destination === "daily" && window.CandleNav) window.CandleNav.go("daily");
        var hooks = finishHooks;
        finishHooks = [];
        hooks.forEach(function (hook) { hook(); });
        if (returnFocus && returnFocus.focus && destination !== "daily") returnFocus.focus();
    }

    nextBtn.addEventListener("click", function () { show(index + 1); });
    backBtn.addEventListener("click", function () { show(index - 1); });
    playBtn.addEventListener("click", function () { close("game", "onboarding-play"); });
    dailyBtn.addEventListener("click", function () { close("daily", "onboarding-daily"); });
    skipBtn.addEventListener("click", function () { close("game", "onboarding-skip"); });

    modal.addEventListener("keydown", function (event) {
        if (event.key === "Escape") {
            event.preventDefault();
            close("game", "onboarding-skip");
            return;
        }
        if (event.key !== "Tab") return;
        // Keep focus inside the card: it is modal, and the page behind it is waiting on it.
        var focusable = Array.prototype.slice.call(
            modal.querySelectorAll("button:not(.hidden)")
        ).filter(function (el) { return el.offsetParent !== null; });
        if (!focusable.length) return;
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });

    /* Reopened by hand from the game toolbar. Nothing waits on it then: a round may already be
       running, and the tour is a reminder rather than a gate — which is also why the clock
       warning is on its first step. */
    if (helpBtn) {
        helpBtn.addEventListener("click", function () {
            if (window.CandleAnalytics) window.CandleAnalytics.track("onboarding-help");
            open();
        });
    }

    function whenFinished() {
        return new Promise(function (resolve) {
            if (modal.classList.contains("hidden")) resolve();
            else finishHooks.push(resolve);
        });
    }

    /* The game's first chart waits for two things: the tour to be done with, and the game view
       to actually be on screen. The second matters when the tour ends on "Thử thách hôm nay" —
       dealing a practice round behind the daily tab would start a clock nobody is watching.

       Resolves to whether the player has just asked to play (closed this tour towards the game),
       in which case the game deals at once; otherwise it shows its start button and waits. */
    function gameReady() {
        var tourOpen = !modal.classList.contains("hidden");
        return whenFinished().then(function () {
            var game = document.getElementById("view-game");
            var askedToPlay = tourOpen && closedTo === "game";
            if (!game.classList.contains("hidden")) return askedToPlay;
            return new Promise(function (resolve) {
                document.addEventListener("candles:view", function onView(event) {
                    if (event.detail.view !== "game") return;
                    document.removeEventListener("candles:view", onView);
                    resolve(false);
                });
            });
        });
    }

    if (isFirstVisit()) {
        if (window.CandleAnalytics) window.CandleAnalytics.track("onboarding-shown");
        open();
    }

    window.CandleOnboarding = { open: open, gameReady: gameReady };
})();
