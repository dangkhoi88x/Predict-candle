// @ts-check
const base = require("@playwright/test");
const { expect } = base;

/**
 * The page the app serves, and nothing else. The ticker and the crypto heatmap call CoinGecko
 * straight from the browser, and a test that depends on a third party answering is a test that
 * fails when it does not — so every request off localhost is refused, on every test.
 */
const test = base.test.extend({
    context: async ({ context }, use) => {
        await context.route((url) => url.hostname !== "localhost", (route) => route.abort());
        await use(context);
    },
});

/**
 * How long "nothing happened" is given to happen. The page never goes network-idle — the live
 * banner polls — so a test that something was *not* requested waits this long and then counts.
 */
const QUIET_MS = 1500;

/**
 * Marks the visitor as having seen the tour. Every test except the tour's own starts here: the
 * tour is a dialog, and while it is up the game deals nothing and the keyboard is its.
 */
async function skipTour(page) {
    await page.addInitScript(() => {
        try { localStorage.setItem("candles-onboarded", "1"); } catch (e) { /* private mode */ }
    });
}

/** Counts requests whose path matches, from the moment this is called. */
function countRequests(page, pattern) {
    const seen = [];
    page.on("request", (request) => {
        if (pattern.test(new URL(request.url()).pathname)) seen.push(request);
    });
    return seen;
}

/**
 * Answers one guess by pressing `button` the way a player would, and waits for the server's
 * verdict. The pause is `candles.round.timing.min-think-time` (250 ms) with room to spare: the
 * server refuses an answer that arrives sooner, which is the floor that stops a script.
 */
async function guess(page, button, endpoint) {
    await expect(button).toBeEnabled();
    await page.waitForTimeout(400);
    const [response] = await Promise.all([
        page.waitForResponse((r) => r.url().includes(endpoint) && r.request().method() === "POST"),
        button.click(),
    ]);
    expect(response.status()).toBe(200);
    return response.json();
}

/** Plays guesses until the server says the chart is over; returns the last verdict. */
async function playToTheEnd(page, button, endpoint) {
    for (let i = 0; i < 10; i++) {
        const verdict = await guess(page, button, endpoint);
        if (verdict.sessionComplete) return verdict;
    }
    throw new Error("the chart never finished");
}

module.exports = { test, expect, QUIET_MS, skipTour, countRequests, guess, playToTheEnd };
