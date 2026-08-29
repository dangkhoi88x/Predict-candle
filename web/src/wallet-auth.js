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
    signInWithWallet(address);
});

async function signInWithWallet(address) {
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
            throw new Error("Bạn đã từ chối yêu cầu ký, hoặc ví báo lỗi khi ký.");
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
};
