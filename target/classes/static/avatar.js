(function () {
    "use strict";

    /* One face per player, drawn from their name and nothing else.
     *
     * Two boards now show the same people — the play tab's five-row card and the leaderboard —
     * and they have to agree: a player who is a fox on one and an owl on the other reads as two
     * different players. Hashing the name is what makes them agree with no avatar column, no
     * upload, no storage and no request; the same name gives the same face on every device and
     * on a page nobody had to sign in to open.
     *
     * The name, not the wallet: the live roster hashes `walletShort` on purpose, so a renamed
     * account keeps the face it always had. Here there is no wallet to hash — the board sends a
     * display name and nothing else — so the trade is different and a rename changes the face.
     * That is the honest limit of what this endpoint carries.
     */

    /* Twenty-four rather than a handful: a fifty-row board draws enough faces that a short
       list puts the same animal three or four times on one screen, which is exactly the
       sameness the faces exist to break up. */
    var EMOJI = ["🦊", "🐼", "🦉", "🐙", "🦁", "🐳", "🦄", "🐢", "🦅", "🐝",
                 "🐸", "🦋", "🐬", "🦌", "🐺", "🦔", "🐧", "🦈", "🐨", "🦖",
                 "🦩", "🐇", "🦭", "🐡"];

    function hash(name) {
        var h = 0;
        for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
        return h;
    }

    /* The colour is a hue rather than a palette entry: two dozen faces on two dozen fixed tints
       would collide visibly on a fifty-row board, and a hue picked from the same hash gives
       every row its own without a table to maintain. Saturation and lightness are fixed so
       none of them can come out brighter than the text on top of it. */
    function node(name) {
        var h = hash(name || "");
        var el = document.createElement("span");
        el.className = "avatar";
        el.textContent = EMOJI[h % EMOJI.length];
        el.style.background = "hsl(" + (h % 360) + " 60% 50% / 0.22)";
        el.setAttribute("aria-hidden", "true");
        return el;
    }

    window.CandleAvatar = { node: node };
})();
