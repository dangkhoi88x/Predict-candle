/**
 * Keyboard shortcuts for the two games: CandleKeys.bind(view, map).
 *
 * A shortcut **presses the button a mouse would press**, and that is the whole design. The map
 * holds elements, never functions, so nothing about a round is written twice: unlocking sound,
 * the miss count, the funnel event and the recording all stay behind the click handlers that
 * already own them. A key that reaches a hidden or disabled button does nothing, which is also
 * what a mouse does — so "is the round still open" needs no separate answer here.
 *
 * A value may be a list, meaning "whichever of these is actionable": Space on the game tab is
 * start when the gate is up and a new chart once one is finished, which is what the one key a
 * player reaches for should mean at each moment.
 *
 * Five things are deliberately ignored, each for a reason found by trying it:
 *
 * - **A focused button or link.** Those answer Enter and Space themselves, so a key handled
 *   here as well would press twice.
 * - **Typing.** An input, a textarea, a select or anything contenteditable keeps its keys, or
 *   naming a challenge would place a call halfway through the word.
 * - **The rail.** Its tablist moves the selection with the same arrows (roving tabindex), so a
 *   keystroke aimed at navigation must not also answer the chart behind it.
 * - **A modifier.** Cmd/Ctrl/Alt belong to the browser: ctrl+L is the address bar, and a page
 *   that swallows it is a page people stop trusting with their keys.
 * - **Another view, or a dialog over this one.** The countdown runs on whatever chart is dealt,
 *   so a key pressed while reading the blog — or while the tour is open on a first visit — must
 *   not spend a guess the player never saw.
 */
(function () {
    "use strict";

    var bindings = [];

    /* nav.js announces a switch but not the view the markup shipped showing, so the first one is
       read off the page: the one panel without .hidden. */
    var activeView = (function () {
        var shown = document.querySelector('main[id^="view-"]:not(.hidden)');
        return shown ? shown.id.replace("view-", "") : null;
    })();

    function typing(node) {
        if (!node) return false;
        if (node.isContentEditable) return true;
        var tag = node.tagName;
        return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
    }

    /** Actionable means what a mouse would find: on the page, not hidden, not disabled. */
    function pressable(element) {
        return !!element && !element.disabled && !!element.offsetParent;
    }

    function target(value) {
        var candidates = Array.isArray(value) ? value : [value];
        for (var i = 0; i < candidates.length; i++) {
            if (pressable(candidates[i])) return candidates[i];
        }
        return null;
    }

    document.addEventListener("keydown", function (event) {
        if (event.ctrlKey || event.metaKey || event.altKey) return;
        if (typing(event.target)) return;
        /* A focused button or link already answers Enter and Space itself; handling them here
           too would place the same call twice. */
        if (event.target && event.target.closest && event.target.closest("button, a, summary")) return;
        if (event.target && event.target.closest && event.target.closest('[role="tablist"]')) return;
        /* A dialog over the page owns the keyboard while it is up — the first-visit tour, whose
           "Chơi thử ngay" is a button a Space press would otherwise race. */
        if (document.querySelector('[role="dialog"]:not(.hidden)')) return;

        for (var i = 0; i < bindings.length; i++) {
            var binding = bindings[i];
            if (binding.view !== activeView) continue;
            var value = binding.map[event.key];
            if (value === undefined) continue;
            var element = target(value);
            if (!element) return;
            event.preventDefault();
            element.click();
            return;
        }
    });

    document.addEventListener("candles:view", function (event) {
        activeView = event.detail && event.detail.view;
    });

    window.CandleKeys = {
        /**
         * @param view the nav view name these keys belong to, as `candles:view` reports it
         * @param map  {"ArrowUp": element, "l": [element, element], …}, keys as KeyboardEvent.key
         */
        bind: function (view, map) {
            bindings.push({ view: view, map: map });
        }
    };
})();
