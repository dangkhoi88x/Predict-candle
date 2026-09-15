(function () {
    "use strict";

    /* The same page, opened as a Telegram Mini App.

       Telegram launches a Mini App at the site's own URL with the launch data in the fragment
       (`#tgWebAppData=…&tgWebAppVersion=…`), and a `startapp` link adds `?tgWebAppStartParam=…`.
       Nothing here runs outside Telegram: the check below reads the URL, and only when it matches
       is Telegram's own script fetched — a visitor in a browser pays for none of this.

       Inside, three things change and nothing else:

         sign-in     the launch's signed initData goes to POST /api/auth/telegram, so a player in
                     Telegram has an account without a wallet. Only when no session was restored:
                     a cookie naming an account wins over a launch that names a different one.
         start_param `thach_<id>` opens that challenge, `daily` / `live` open those views — the
                     t.me link cannot carry `?thach=` itself, so this is how a shared challenge
                     arrives in Telegram.
         sharing     CandleTelegram.share opens Telegram's own share sheet, with a t.me link that
                     opens the challenge back inside Telegram when the deployment names its app.

       A failure anywhere leaves the ordinary web page, which is fully playable on its own. */

    var SDK_URL = "https://telegram.org/js/telegram-web-app.js";

    var launchParams = readLaunchParams();
    var inside = !!launchParams.get("tgWebAppData") || !!launchParams.get("tgWebAppPlatform");
    var config = null;
    var sdk = null;

    function readLaunchParams() {
        var params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
        try {
            new URLSearchParams(window.location.search).forEach(function (value, key) {
                if (/^tgWebApp/.test(key)) params.set(key, value);
            });
        } catch (e) {
            // A URL this cannot read is not a Telegram launch.
        }
        return params;
    }

    function loadSdk() {
        if (window.Telegram && window.Telegram.WebApp) return Promise.resolve(window.Telegram.WebApp);
        return new Promise(function (resolve, reject) {
            var script = document.createElement("script");
            script.src = SDK_URL;
            script.onload = function () {
                if (window.Telegram && window.Telegram.WebApp) resolve(window.Telegram.WebApp);
                else reject(new Error("Telegram WebApp missing"));
            };
            script.onerror = function () { reject(new Error("Telegram script failed to load")); };
            document.head.appendChild(script);
        });
    }

    function loadConfig() {
        return fetch("/api/site-config")
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (body) { return (body && body.telegram) || null; })
            .catch(function () { return null; });
    }

    /* The player's own theme choice stays theirs; without one, the page follows Telegram's
       rather than the phone's, since Telegram's is what surrounds it. */
    function applyTheme(webApp) {
        var stored = null;
        try {
            stored = localStorage.getItem("candles-theme");
        } catch (e) {
            // Storage blocked: follow Telegram.
        }
        if (!stored && (webApp.colorScheme === "light" || webApp.colorScheme === "dark")) {
            document.documentElement.setAttribute("data-theme", webApp.colorScheme);
        }
        var bg = getComputedStyle(document.body).backgroundColor;
        var hex = toHex(bg);
        try {
            if (hex) {
                webApp.setHeaderColor(hex);
                webApp.setBackgroundColor(hex);
            }
        } catch (e) {
            // Older clients refuse a hex colour; their default header is fine.
        }
    }

    function toHex(rgb) {
        var m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(rgb || "");
        if (!m) return null;
        return "#" + [m[1], m[2], m[3]].map(function (n) {
            return ("0" + Number(n).toString(16)).slice(-2);
        }).join("");
    }

    async function signIn(initData) {
        var auth = window.CandleAuth;
        if (!auth || !initData) return;
        await auth.whenRestored();
        if (auth.getUser()) return;
        try {
            var res = await fetch("/api/auth/telegram", {
                method: "POST",
                credentials: "include",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ initData: initData }),
            });
            if (!res.ok) return; // Expired or not configured: the page is still playable signed out.
            auth.applySession(await res.json());
            if (window.CandleAnalytics) window.CandleAnalytics.track("telegram-sign-in");
        } catch (e) {
            // Offline or blocked — stay signed out.
        }
    }

    function openStartParam(param) {
        if (!param) return;
        var challenge = /^thach_([a-z0-9]{4,16})$/.exec(param);
        if (challenge && window.CandleDaily && window.CandleDaily.openChallenge) {
            window.CandleDaily.openChallenge(challenge[1]);
        } else if ((param === "daily" || param === "live") && window.CandleNav) {
            window.CandleNav.go(param);
        }
    }

    /**
     * Telegram's share sheet for {text, path}. Returns false when not inside Telegram (or its
     * script never loaded), so the caller falls back to the browser's own share or the clipboard.
     * A challenge path becomes a t.me link that reopens it inside Telegram when the app is named.
     */
    function share(text, path) {
        if (!sdk || typeof sdk.openTelegramLink !== "function") return false;
        var url = window.location.origin + path;
        var challenge = /[?&]thach=([a-z0-9]{4,16})/.exec(path);
        if (challenge && config && config.appLink) {
            url = config.appLink + "?startapp=thach_" + challenge[1];
        }
        sdk.openTelegramLink("https://t.me/share/url?url=" + encodeURIComponent(url)
            + "&text=" + encodeURIComponent(text));
        return true;
    }

    window.CandleTelegram = {
        inside: function () { return inside; },
        share: share,
    };

    if (!inside) return;
    document.documentElement.classList.add("in-telegram");

    Promise.all([loadSdk(), loadConfig()]).then(function (loaded) {
        sdk = loaded[0];
        config = loaded[1];
        try {
            sdk.ready();
            sdk.expand();
        } catch (e) {
            // An old client without these still shows the page.
        }
        applyTheme(sdk);

        var initData = sdk.initData || launchParams.get("tgWebAppData");
        if (config && config.login) signIn(initData);

        var start = (sdk.initDataUnsafe && sdk.initDataUnsafe.start_param) || launchParams.get("tgWebAppStartParam");
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", function () { openStartParam(start); });
        } else {
            openStartParam(start);
        }
    }).catch(function () {
        // Telegram's script is unreachable: the ordinary web page is what they get.
    });
})();
