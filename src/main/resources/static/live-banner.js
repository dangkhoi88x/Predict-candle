(function () {
    "use strict";

    /* The topbar's live pill and the rail's live tag, both counting down to the same lock.
       This is deliberately not part of live.js: that module is deferred until the live tab is
       first opened (nav.js's onFirstShow), and the whole point of the banner is to say there
       is a round running to someone who has not opened that tab. Waking live.js up on load to
       feed it would undo the deferral and drag its 3s poll, its history fetch and its chart
       onto every visit.

       So this asks one public endpoint for one asset, and asks it rarely. The clock in the
       text is run locally off the lock time; only a crossed deadline costs a request. */

    var banner = document.getElementById("live-banner");
    var text = document.getElementById("live-banner-text");
    var railTag = document.getElementById("rail-tag-live");

    var ASSET = "BTCUSDT";
    var REFETCH_MS = 60000;
    var LOCKED_REFETCH_MS = 15000;

    var round = null;
    var refetchAt = 0;
    /* True once a fetch has failed with nothing on screen. Until then the pill stays, because it
       ships visible: appearing only after the fetch pushed the whole page down — by a row on a
       phone, where it sits on a line of its own — every time the site loaded. */
    var unavailable = false;
    var cta = banner.querySelector(".live-banner-cta");

    function hide() {
        banner.classList.add("hidden");
        railTag.classList.add("hidden");
    }

    function fetchRound() {
        return fetch("/api/live/round?asset=" + ASSET)
            .then(function (res) {
                if (!res.ok) throw new Error("live round unavailable");
                return res.json();
            })
            .then(function (data) {
                round = data;
                unavailable = false;
                refetchAt = Date.now() + REFETCH_MS;
            })
            .catch(function () {
                if (!round) unavailable = true;
                /* Transient, or the endpoint is down. Leave whatever is on screen alone and
                   try again on the next window rather than blanking a working countdown. */
                refetchAt = Date.now() + REFETCH_MS;
            });
    }

    function tick() {
        var now = Date.now();
        if (now >= refetchAt) fetchRound();
        if (!round) {
            if (unavailable) hide();
            return;
        }

        /* Past the lock the round is settling and there is nothing left to place. The pill says
           when the next one opens instead of vanishing: vanishing for eight minutes of every hour
           moved the page each time, and "nothing to place now, the next round is in 6:12" is
           still worth a glance. The rail's tag, which counts down to a lock, goes. */
        var left = new Date(round.lockAt).getTime() - now;
        if (left <= 0) {
            var opens = new Date(round.closeAt).getTime() - now;
            text.textContent = "Vòng #" + round.roundNumber + " đã chốt · vòng mới sau "
                + window.CandleFormat.clock(Math.max(0, opens));
            if (cta) cta.textContent = "Xem →";
            banner.classList.remove("hidden");
            railTag.classList.add("hidden");
            /* The snapshot the clock just ran out on is stale, so pull the next round in
               sooner than the idle window — but not every second: while a round sits locked
               the server has nothing new to say for the whole eight minutes. */
            refetchAt = Math.min(refetchAt, now + LOCKED_REFETCH_MS);
            return;
        }

        text.textContent = "Vòng live #" + round.roundNumber + " chốt sau " + window.CandleFormat.clock(left);
        if (cta) cta.textContent = "Vào đặt →";
        banner.classList.remove("hidden");
        railTag.textContent = window.CandleFormat.clock(left);
        railTag.classList.remove("hidden");
    }

    text.textContent = "Đang tải vòng live…";
    fetchRound().then(function () {
        tick();
        setInterval(tick, 1000);
    });
})();
