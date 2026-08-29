import { createAppKit } from "@reown/appkit";
import { mainnet, arbitrum, optimism, polygon, bsc } from "@reown/appkit/networks";
import { EthersAdapter } from "@reown/appkit-adapter-ethers";
import { hexlify, toUtf8Bytes } from "ethers";

const PROJECT_ID = "2c3bd10f16aa8b5582bfbd9c36e1f0c3";
const API_BASE = "/api/auth";

const appKit = createAppKit({
    adapters: [new EthersAdapter()],
    networks: [mainnet, arbitrum, optimism, polygon, bsc],
    projectId: PROJECT_ID,
    metadata: {
        name: "Candle Guess",
        description: "Luyện đọc mẫu nến & mẫu hình kỹ thuật",
        url: window.location.origin,
        icons: [],
    },
    themeMode: "dark",
    features: {
        email: true,
        socials: ["google", "x", "discord", "github"],
        emailShowWallets: true,
    },
});

// AppKit's social-login flow (Google/email) opens a popup that talks to a WalletConnect/Reown
// relay running in its own cross-origin iframe. When that popup is blocked or the OAuth
// round-trip times out, the failure is logged *inside that iframe's own console* — a
// different JS realm entirely, so none of window's error/unhandledrejection/console hooks in
// our page can ever see it (confirmed by testing: neither caught it). Instead of trying to
// catch AppKit's internal failure, we run our own watchdog tied to the modal being open: if
// nothing connects within CONNECT_TIMEOUT_MS of opening it, we close it and tell the user
// ourselves — this un-sticks the UI regardless of *why* the connection attempt stalled.
const CONNECT_TIMEOUT_MS = 45000;
let connectWatchdog = null;

function clearConnectWatchdog() {
    if (connectWatchdog) {
        clearTimeout(connectWatchdog);
        connectWatchdog = null;
    }
}

function armConnectWatchdog() {
    clearConnectWatchdog();
    connectWatchdog = setTimeout(function () {
        connectWatchdog = null;
        appKit.close();
        if (window.CandleAuth) {
            window.CandleAuth.showError("Kết nối ví quá thời gian chờ (có thể do popup Google/social bị chặn). Hãy cho phép popup rồi thử lại, hoặc dùng MetaMask/WalletConnect trực tiếp.");
        }
    }, CONNECT_TIMEOUT_MS);
}

appKit.subscribeState((state) => {
    if (!state.open) clearConnectWatchdog(); // modal closed, whether by us, the user, or a successful connect
});

let eip155Provider = null;
let signedInAddress = null; // address we've already completed the sign-in flow for
// AppKit emits account updates repeatedly while it fills in the connector id, balance,
// profile name, etc. Without this guard each emission started its own sign-in: every run
// requested a fresh nonce, which overwrote the previous one server-side, so the signature
// from run N was checked against run N+1's nonce and always failed with "signature does not
// recover" / "no pending nonce" — and the wallet showed a sign prompt per run.
let signInInFlight = false;

appKit.subscribeProviders((state) => {
    eip155Provider = state["eip155"];
});

appKit.subscribeAccount((state) => {
    if (!state.isConnected || !state.address) {
        signedInAddress = null;
        return;
    }
    clearConnectWatchdog();
    var address = state.address.toLowerCase();
    if (address === signedInAddress) return; // already signed in for this address
    if (signInInFlight) return; // a sign-in is already running for this connection
    signInWithWallet(address);
});

async function signInWithWallet(address) {
    signInInFlight = true;
    try {
        var nonceRes = await fetch(API_BASE + "/wallet/nonce?address=" + address);
        if (!nonceRes.ok) throw new Error("Không lấy được nonce từ máy chủ.");
        var nonceBody = await nonceRes.json();

        var signature;
        try {
            signature = await eip155Provider.request({
                method: "personal_sign",
                params: [hexlify(toUtf8Bytes(nonceBody.message)), address],
            });
        } catch (signError) {
            console.error("[CandleWallet] personal_sign failed:", signError);
            var detail = signError && (signError.message || (signError.error && signError.error.message));
            var code = signError && (signError.code || (signError.error && signError.error.code));
            throw new Error("Ký thất bại" + (detail ? ": " + detail : "") + (code !== undefined ? " (mã " + code + ")" : "") + ".");
        }

        var verifyRes = await fetch(API_BASE + "/wallet/verify", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            credentials: "include",
            body: JSON.stringify({ address: address, signature: signature }),
        });
        if (!verifyRes.ok) {
            var errorBody = await verifyRes.json().catch(function () { return null; });
            throw new Error((errorBody && errorBody.message) || "Máy chủ từ chối chữ ký (HTTP " + verifyRes.status + ").");
        }

        signedInAddress = address;
        var session = await verifyRes.json();
        if (window.CandleAuth) window.CandleAuth.applySession(session);
    } catch (e) {
        // User rejected the signature request, or the backend call failed — disconnect so
        // the wallet doesn't sit "connected" in the UI without a matching backend session.
        signedInAddress = null;
        appKit.disconnect();
        if (window.CandleAuth) {
            window.CandleAuth.showError((e && e.message) || "Đăng nhập bằng ví thất bại. Vui lòng thử lại.");
        }
    } finally {
        signInInFlight = false;
        // Close the modal ourselves rather than trusting AppKit to do it. While it is open
        // it covers the whole viewport with pointer-events:auto, so a modal left behind by
        // the signing step silently swallows every click on the game below it.
        appKit.close();
    }
}

window.CandleWallet = {
    connect: function () {
        armConnectWatchdog();
        appKit.open();
    },
    disconnect: function () {
        clearConnectWatchdog();
        signedInAddress = null;
        appKit.disconnect();
    },
    /**
     * Opens AppKit's own account screen (balance, network, copy address, disconnect).
     * Our backend session outlives the wallet connection — the refresh cookie can restore a
     * session on page load before/without AppKit reconnecting — so check AppKit's state
     * first, otherwise open() would drop the user on the "Connect" screen with no
     * explanation of why their wallet details aren't showing.
     */
    openAccount: function () {
        if (!appKit.getIsConnectedState()) {
            if (window.CandleAuth) {
                window.CandleAuth.showError("Ví chưa được kết nối lại trong phiên này. Hãy kết nối ví để xem chi tiết.");
            }
            armConnectWatchdog();
            appKit.open();
            return;
        }
        appKit.open({ view: "Account" });
    },
};
