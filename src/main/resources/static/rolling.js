/* Odometer-style numbers, shared by anything that shows a value which changes in place.
 *
 * Each digit is a 1em-tall window over a stacked 0–9 strip, so updating a value translates
 * the strip instead of replacing text — the number reads as a mechanical roll rather than a
 * flicker. Pair it with the .rolling class in style.css, which supplies the digit boxes and
 * the transition.
 *
 * The container is rebuilt only when the *shape* of the text changes ("$DD,DDD.DD"), not on
 * every update, so a price ticking within the same magnitude never touches the DOM beyond a
 * transform per digit. */
window.CandleRolling = (function () {
    "use strict";

    // "72%" -> "DD%". Two values with the same shape can reuse the same digit elements.
    function shapeOf(text) {
        var out = "";
        for (var i = 0; i < text.length; i++) {
            var c = text[i];
            out += (c >= "0" && c <= "9") ? "D" : c;
        }
        return out;
    }

    function isDigit(c) {
        return c >= "0" && c <= "9";
    }

    function build(container, text) {
        container.innerHTML = "";
        for (var i = 0; i < text.length; i++) {
            var ch = text[i];
            if (isDigit(ch)) {
                var digit = document.createElement("span");
                digit.className = "digit";
                digit.setAttribute("aria-hidden", "true");
                var strip = document.createElement("span");
                strip.className = "digit-strip";
                for (var d = 0; d <= 9; d++) {
                    var cell = document.createElement("span");
                    cell.textContent = String(d);
                    strip.appendChild(cell);
                }
                digit.appendChild(strip);
                container.appendChild(digit);
            } else {
                var lit = document.createElement("span");
                lit.className = "lit";
                lit.textContent = ch;
                lit.setAttribute("aria-hidden", "true");
                container.appendChild(lit);
            }
        }

        /* Every digit carries all ten numerals, so the visible markup reads as "0123456789"
           per position to a screen reader — and these containers are exactly the ones likely
           to be aria-live. Hide the strips and carry the real value in one off-screen node,
           appended last so it stays clear of the index-based update loop below. */
        var sr = document.createElement("span");
        sr.className = "rolling-sr";
        container.appendChild(sr);

        container.dataset.shape = shapeOf(text);
    }

    function update(container, text) {
        if (!container) return;
        text = String(text);
        if (container.dataset.shape !== shapeOf(text)) build(container, text);

        var children = container.children;
        for (var i = 0; i < text.length; i++) {
            var c = text[i];
            var node = children[i];
            if (!node) continue;
            if (isDigit(c)) {
                node.querySelector(".digit-strip").style.transform = "translateY(-" + c + "em)";
            } else if (node.textContent !== c) {
                node.textContent = c;
            }
        }

        var sr = container.lastElementChild;
        if (sr && sr.className === "rolling-sr" && sr.textContent !== text) sr.textContent = text;
    }

    return { update: update };
})();
