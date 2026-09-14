(function () {
    "use strict";

    /* The funnel, measured where the database cannot see it.

       Retention on the admin page starts at a player's first recorded guess — and an anonymous
       guess is never recorded, nor is anyone who opened the page and left. Everything before the
       first signed-in call was invisible. GoatCounter counts page views on its own; this file adds
       the steps in between as named events:

         onboarding-shown → onboarding-play / onboarding-daily / onboarding-skip
         first-guess → chart-complete                (practice, first of each per page load)
         daily-first-guess → daily-complete → daily-share
         sign-in                                     (a wallet sign-in, not a restored session)

       GoatCounter reports each as a path with its total and unique visitors per day, so the
       funnel is read by comparing those counts — no cookies, no personal data, nothing that needs
       a consent banner.

       Off unless the deployment names a site (`/api/site-config`), so a checkout counts nothing.
       Events raised before the counter has loaded are queued, and dropped if it never does:
       analytics must never be the reason anything on the page fails. */

    var queue = [];
    var ready = false;
    var disabled = false;
    var onceFired = {};

    function send(name) {
        try {
            window.goatcounter.count({ path: name, title: name, event: true });
        } catch (e) {
            // A blocked or broken counter is not the page's problem.
        }
    }

    function flush() {
        ready = true;
        queue.splice(0).forEach(send);
    }

    function track(name) {
        if (disabled) return;
        if (ready) send(name);
        else queue.push(name);
    }

    /** For steps that matter once per page load — the first guess, not every guess. */
    function trackOnce(name) {
        if (onceFired[name]) return;
        onceFired[name] = true;
        track(name);
    }

    function load(endpoint) {
        var script = document.createElement("script");
        script.async = true;
        script.src = "https://gc.zgo.at/count.js";
        script.setAttribute("data-goatcounter", endpoint);
        script.onload = function () {
            if (window.goatcounter && window.goatcounter.count) flush();
            else disabled = true;
        };
        script.onerror = function () {
            disabled = true;
            queue = [];
        };
        document.head.appendChild(script);
    }

    fetch("/api/site-config")
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (config) {
            if (config && config.goatcounter) {
                load(config.goatcounter);
            } else {
                disabled = true;
                queue = [];
            }
        })
        .catch(function () {
            disabled = true;
            queue = [];
        });

    window.CandleAnalytics = { track: track, trackOnce: trackOnce };
})();
