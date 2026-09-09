(function () {
    "use strict";

    /* The game's sound and haptics.
     *
     * Synthesised rather than loaded: five short tones cost nothing to ship and a sound file
     * that has not downloaded yet is a sound that arrives after the candle it was reacting to.
     * The whole point of these is that they land on the reveal.
     *
     * Lifted out of app.js, which had grown to a thousand-odd lines covering six unrelated
     * jobs. This one had no ties to any of the others: it reads a toggle, a storage key and an
     * AudioContext, and nothing about a round or a chart.
     */

    var MUTE_KEY = "candleGuess.muted.v1";

    var muted = false;
    try {
        muted = localStorage.getItem(MUTE_KEY) === "1";
    } catch (e) {
        // Storage blocked (private mode). Defaulting to sound on is the same as a first visit.
    }

    var ctx = null;
    var toggleEl = null;

    /**
     * A browser will not let a page make noise before the visitor has interacted with it, and
     * a context created too early lands in "suspended" and stays there. So this is called from
     * the click that starts a round rather than at load: by then there has been a gesture, and
     * resuming is allowed.
     */
    function unlock() {
        if (muted) return;
        if (!ctx) {
            var Ctx = window.AudioContext || window.webkitAudioContext;
            if (!Ctx) return;
            ctx = new Ctx();
        }
        if (ctx.state === "suspended") ctx.resume();
    }

    /* One note. The gain ramps up over 12ms rather than starting at full — an oscillator that
       begins at its peak clicks — and decays exponentially, which is how a struck thing
       actually fades. */
    function tone(freq, startDelay, duration, type, peakGain) {
        if (muted || !ctx) return;
        var startAt = ctx.currentTime + startDelay;
        var osc = ctx.createOscillator();
        var gain = ctx.createGain();
        osc.type = type;
        osc.frequency.value = freq;
        gain.gain.setValueAtTime(0, startAt);
        gain.gain.linearRampToValueAtTime(peakGain, startAt + 0.012);
        gain.gain.exponentialRampToValueAtTime(0.0001, startAt + duration);
        osc.connect(gain).connect(ctx.destination);
        osc.start(startAt);
        osc.stop(startAt + duration + 0.02);
    }

    /** A rising major third: the shape everything from a doorbell up uses to mean "yes". */
    function correct() {
        tone(880, 0, 0.11, "sine", 0.11);
        tone(1318.5, 0.08, 0.16, "sine", 0.09);
    }

    /** Low and sawtooth, which is the opposite of the above on both counts. */
    function wrong() {
        tone(196, 0, 0.22, "sawtooth", 0.07);
    }

    /** The end of a chart: an arpeggio for a good session, two falling notes for a bad one. */
    function summary(outcome) {
        if (outcome === "correct") {
            [880, 1108.7, 1318.5].forEach(function (f, i) { tone(f, i * 0.09, 0.16, "sine", 0.1); });
        } else if (outcome === "wrong") {
            tone(220, 0, 0.16, "sawtooth", 0.07);
            tone(164.8, 0.14, 0.28, "sawtooth", 0.07);
        }
    }

    /* Haptics follow the mute switch. They are the same feedback through a different sense, so
       a player who turned the sound off in a quiet room did not ask to be buzzed instead. */
    function vibrate(pattern) {
        if (muted || !navigator.vibrate) return;
        navigator.vibrate(pattern);
    }

    function render() {
        if (!toggleEl) return;
        toggleEl.textContent = muted ? "🔇" : "🔊";
        toggleEl.setAttribute("aria-pressed", muted ? "true" : "false");
    }

    /**
     * Hands the module its button. It owns the mute state, so it owns the control that sets it
     * — a caller that had to remember to redraw the icon after flipping the flag is a caller
     * that will eventually forget.
     */
    function attachToggle(el) {
        toggleEl = el;
        render();
        el.addEventListener("click", function () {
            muted = !muted;
            try {
                localStorage.setItem(MUTE_KEY, muted ? "1" : "0");
            } catch (e) {
                // Unsaved, so it lasts the visit. Better than refusing to mute at all.
            }
            render();
            if (!muted) unlock();
        });
    }

    window.CandleSound = {
        unlock: unlock,
        correct: correct,
        wrong: wrong,
        summary: summary,
        vibrate: vibrate,
        attachToggle: attachToggle,
    };
})();
