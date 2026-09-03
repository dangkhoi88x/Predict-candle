/**
 * Admin page shell. Its only job is to ask the server what this account may do and show one
 * of three states.
 *
 * The check that matters is GET /api/admin/me: a 200 means the server, reading the role from
 * the database, considers this caller an admin. The role on the client session is not
 * consulted for that — it is a fifteen-minute-old copy from a token, useful for deciding
 * whether to bother asking, useless as an answer.
 */
(function () {
    "use strict";

    var el = {
        anon: document.getElementById("admin-anon"),
        denied: document.getElementById("admin-denied"),
        ok: document.getElementById("admin-ok"),
        who: document.getElementById("admin-who"),
        deniedWho: document.getElementById("admin-denied-who"),
        status: document.getElementById("admin-status"),
        avatar: document.getElementById("adm-avatar"),
        walletMeta: document.getElementById("adm-wallet-meta"),
    };

    /* The one place that decides whether this session is an admin. Everything else on the
       page waits for this rather than asking the server again. */
    function announce(isAdmin) {
        /* The nav, the search box and the panes are drawn off a class rather than by each
           module hiding itself: there is one answer here, and one place that applies it. */
        document.body.classList.toggle("is-admin", isAdmin);
        document.dispatchEvent(new CustomEvent("candles:admin", { detail: { admin: isAdmin } }));
    }

    function show(section) {
        [el.anon, el.denied, el.ok].forEach(function (s) {
            s.classList.toggle("hidden", s !== section);
        });
    }

    function describe(identity) {
        return '<span>Ví <b>' + identity.walletAddress + "</b></span>"
            + "<span>Tên <b>" + identity.displayName + "</b></span>"
            + "<span>Vai trò <b>" + identity.role + "</b></span>";
    }

    /** The sidebar's wallet card: two initials, and `7xKq…4bT9 · ADMIN` underneath. */
    function paintWalletCard(identity) {
        if (el.avatar) {
            el.avatar.textContent = (identity.displayName || "??").slice(0, 2).toUpperCase();
        }
        if (el.walletMeta) {
            var wallet = identity.walletAddress || "";
            var short = wallet.length > 12
                ? wallet.slice(0, 6) + "…" + wallet.slice(-4)
                : wallet;
            el.walletMeta.textContent = short + " · " + identity.role;
            el.walletMeta.title = wallet;
        }
    }

    async function refresh() {
        var user = window.CandleAuth.getUser();
        if (!user) {
            show(el.anon);
            el.status.textContent = "";
            announce(false);
            return;
        }

        el.status.textContent = "Đang kiểm tra quyền…";
        try {
            var res = await window.CandleAuth.authFetch("/api/admin/me");
            if (res.ok) {
                var identity = await res.json();
                el.who.innerHTML = describe(identity);
                paintWalletCard(identity);
                /* Confirmed is the uninteresting answer, and it would otherwise sit at the
                   top of all seven panes forever. The sidebar card says the same thing. */
                show(null);
                el.status.textContent = "";
                announce(true);
                return;
            }
            // 403 is the ordinary answer for a signed-in non-admin, not a failure.
            el.deniedWho.innerHTML = '<span>Ví <b>' + user.walletAddress + "</b></span>"
                + "<span>Vai trò <b>" + (user.role || "USER") + "</b></span>";
            show(el.denied);
            announce(false);
            el.status.textContent = res.status === 403
                ? ""
                : "Máy chủ trả về " + res.status + ".";
        } catch (e) {
            show(el.denied);
            announce(false);
            el.status.textContent = "Không gọi được máy chủ. Thử tải lại trang.";
        }
    }

    document.addEventListener("candles:session", refresh);
    refresh();
})();
