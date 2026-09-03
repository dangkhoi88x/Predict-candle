import { defineConfig } from "vite";
import { resolve } from "node:path";

/**
 * Two bundles, one config, chosen by --mode.
 *
 * IIFE is the only format the static front end can load, and IIFE takes exactly one entry, so
 * these cannot be a single multi-entry build. `npm run build` runs both.
 *
 *   default   src/wallet-auth.js  -> static/wallet-auth.js   (window.CandleWallet)
 *   editor    src/blog-editor.js  -> static/blog-editor.js   (window.CandleEditor)
 *
 * The editor bundle is admin-only and the admin page is noindex, so its size is a rounding
 * error next to what the public page ships. That is the whole reason the public blog renders
 * the same documents with its own small walker instead of loading any of this.
 */
const BUNDLES = {
    wallet: { entry: "src/wallet-auth.js", name: "CandleWallet", file: "wallet-auth.js" },
    editor: { entry: "src/blog-editor.js", name: "CandleEditor", file: "blog-editor.js" },
};

export default defineConfig(({ mode }) => {
    const bundle = BUNDLES[mode] || BUNDLES.wallet;
    return {
        build: {
            outDir: resolve(__dirname, "../src/main/resources/static"),
            emptyOutDir: false,
            lib: {
                entry: resolve(__dirname, bundle.entry),
                name: bundle.name,
                formats: ["iife"],
                fileName: () => bundle.file,
            },
        },
    };
});
