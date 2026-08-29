/**
 * Session/UI layer for wallet login. The actual wallet connection + message signing lives in
 * wallet-auth.js (built from web/ via Vite, since the Reown AppKit SDK needs a bundler) — that
 * script calls window.CandleAuth.applySession(...) once the backend verifies the signature.
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
        user: null, // { userId, walletAddress, displayName }
    };

    var loginBtn = document.getElementById("auth-login-btn");
    var logoutBtn = document.getElementById("auth-logout-btn");
    var userBox = document.getElementById("auth-user");
    var displayNameEl = document.getElementById("auth-display-name");
    var errorEl = document.getElementById("auth-error-msg");
    var errorHideTimer = null;

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

    function applySession(response) {
        state.accessToken = response.accessToken;
        state.user = { userId: response.userId, walletAddress: response.walletAddress, displayName: response.displayName };
        renderAuthUi();
    }

    function clearSession() {
        state.accessToken = null;
        state.user = null;
        renderAuthUi();
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

    loginBtn.addEventListener("click", function () {
        if (window.CandleWallet) window.CandleWallet.connect();
    });
    logoutBtn.addEventListener("click", logout);

    tryRestoreSession();

    window.CandleAuth = {
        applySession: applySession,
        clearSession: clearSession,
        showError: showError,
        getAccessToken: function () { return state.accessToken; },
        getUser: function () { return state.user; },
    };
})();
