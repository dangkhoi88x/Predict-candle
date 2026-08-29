import { defineConfig } from "vite";
import { resolve } from "node:path";

export default defineConfig({
    build: {
        outDir: resolve(__dirname, "../src/main/resources/static"),
        emptyOutDir: false,
        lib: {
            entry: resolve(__dirname, "src/wallet-auth.js"),
            name: "CandleWallet",
            formats: ["iife"],
            fileName: () => "wallet-auth.js",
        },
    },
});
