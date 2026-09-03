/**
 * Session/UI layer for wallet login. The actual wallet connection + message signing lives in
 * wallet-auth.js (built from web/ via Vite, since the Reown AppKit SDK needs a bundler) — that
 * script calls window.CandleAuth.applySession(...) once the backend verifies the signature.
 *
 * That bundle is 4 MB (1.1 MB over the wire) and initialises Reown AppKit the moment it runs,
 * which also fires off requests to three of their hosts. Loading it up front made every
 * visitor pay for it, including the ones who never touch a wallet — so it is fetched on the
 * first gesture that actually needs it, and window.CandleWallet does not exist before then.
 *
 * The access token lives only in memory (never localStorage — smaller XSS blast radius). The
 * refresh token is an HttpOnly cookie the browser sends automatically, so on page load we
 * silently POST /api/auth/refresh to recover a session without asking the user to reconnect.
 */
(function () {
    "use strict";

    var API_BASE = "/api/auth";

    var state = {
        accessToken: null,
        user: null, // { userId, walletAddress, displayName, role }
    };

    var loginBtn = document.getElementById("auth-login-btn");
    var logoutBtn = document.getElementById("auth-logout-btn");
    var userBox = document.getElementById("auth-user");
    var displayNameEl = document.getElementById("auth-display-name");
    var errorEl = document.getElementById("auth-error-msg");
    var errorHideTimer = null;

    /* Injecting a script tag rather than import()ing: the bundle is built as an IIFE that
       assigns window.CandleWallet, so there is no module to import. The promise is cached so
       repeated clicks share one download, and cleared on failure so a retry can succeed. */
    var walletLoader = null;

    function loadWallet() {
        if (window.CandleWallet) return Promise.resolve(window.CandleWallet);
        if (walletLoader) return walletLoader;

        walletLoader = new Promise(function (resolve, reject) {
            var script = document.createElement("script");
            script.src = "wallet-auth.js";
            script.onload = function () {
                if (window.CandleWallet) resolve(window.CandleWallet);
                else reject(new Error("Thư viện ví tải xong nhưng không khởi tạo được."));
            };
            script.onerror = function () {
                walletLoader = null;
                reject(new Error("Không tải được thư viện ví. Kiểm tra kết nối mạng rồi thử lại."));
            };
            document.head.appendChild(script);
        });
        return walletLoader;
    }

    /* The download is around 1.1 MB, so on a phone the gap between the click and the modal is
       long enough that an unchanged button reads as broken. */
    async function withWallet(button, run) {
        var label = button.textContent;
        button.disabled = true;
        if (button === loginBtn) button.textContent = "Đang tải…";
        try {
            run(await loadWallet());
        } catch (e) {
            showError(e.message);
        } finally {
            button.disabled = false;
            button.textContent = label;
        }
    }

    function showError(message) {
        errorEl.textContent = message;
        errorEl.classList.remove("hidden");
        clearTimeout(errorHideTimer);
        errorHideTimer = setTimeout(function () {
            errorEl.classList.add("hidden");
        }, 6000);
    }

    function renderAuthUi() {
        var authenticated = !!state.user;
        loginBtn.classList.toggle("hidden", authenticated);
        userBox.classList.toggle("hidden", !authenticated);
        if (authenticated) {
            displayNameEl.textContent = state.user.displayName;
        }
    }

    /* Broadcast rather than call into app.js directly: the scoreboard is not the only thing
       that will care who is signed in, and auth.js should not have to know about any of them. */
    function announceSession() {
        /* Convenience, not a gate: the link is hidden for everyone else, but the page it
           points at asks the server again and every admin route enforces on its own. */
        var adminLink = document.getElementById("admin-link");
        if (adminLink) {
            adminLink.classList.toggle("hidden", !state.user || state.user.role !== "ADMIN");
        }

        document.dispatchEvent(new CustomEvent("candles:session", { detail: { user: state.user } }));
    }

    function applySession(response) {
        var wasSignedIn = !!state.user;
        state.accessToken = response.accessToken;
        /* role is what the server last told us this account is. It decides whether the
           admin link is drawn and nothing else — every admin route checks for itself. */
        state.user = {
            userId: response.userId,
            walletAddress: response.walletAddress,
            displayName: response.displayName,
            role: response.role,
        };
        renderAuthUi();
        // The 10-minute token renewal also lands here; only a real sign-in is news.
        if (!wasSignedIn) announceSession();
    }

    function clearSession() {
        var wasSignedIn = !!state.user;
        state.accessToken = null;
        state.user = null;
        renderAuthUi();
        if (wasSignedIn) announceSession();
    }

    async function logout() {
        try {
            await fetch(API_BASE + "/logout", { method: "POST", credentials: "include" });
        } catch (e) {
            // Clear client-side state regardless of network errors.
        }
        if (window.CandleWallet) window.CandleWallet.disconnect();
        clearSession();
    }

    async function tryRestoreSession() {
        try {
            var res = await fetch(API_BASE + "/refresh", { method: "POST", credentials: "include" });
            if (res.ok) {
                applySession(await res.json());
            }
        } catch (e) {
            // No valid session cookie (or offline) — stay logged out silently.
        }
    }

    /* The access token lasts 15 minutes. Practice endpoints are permitAll, so an expired one
       does not fail loudly — the request still succeeds and the result quietly goes
       unrecorded. Renewing well inside the window is cheaper than detecting that. */
    var REFRESH_EVERY_MS = 10 * 60 * 1000;

    setInterval(function () {
        // Nothing to renew when logged out, and a background tab is not playing.
        if (state.accessToken && !document.hidden) tryRestoreSession();
    }, REFRESH_EVERY_MS);

    loginBtn.addEventListener("click", function () {
        withWallet(loginBtn, function (wallet) { wallet.connect(); });
    });
    displayNameEl.addEventListener("click", function () {
        withWallet(displayNameEl, function (wallet) { wallet.openAccount(); });
    });
    logoutBtn.addEventListener("click", logout);

    tryRestoreSession();

    window.CandleAuth = {
        applySession: applySession,
        clearSession: clearSession,
        showError: showError,
        getAccessToken: function () { return state.accessToken; },
        getUser: function () { return state.user; },
        isAdmin: function () { return !!state.user && state.user.role === "ADMIN"; },

        /**
         * fetch() with the bearer token attached when there is one. Without a session it is
         * a plain fetch, which is what keeps practice playable for anonymous visitors —
         * callers do not branch on whether anyone is logged in.
         */
        authFetch: function (url, options) {
            options = options || {};
            if (state.accessToken) {
                options.headers = Object.assign({}, options.headers, {
                    Authorization: "Bearer " + state.accessToken,
                });
            }
            return fetch(url, options);
        },
    };
})();
