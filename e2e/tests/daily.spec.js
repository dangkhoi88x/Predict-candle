// @ts-check
const { test, expect, skipTour, countRequests, playToTheEnd } = require("./helpers");

/*
 * The daily played signed out. Nothing is recorded for anonymous play, so the server would hand
 * today's chart back as though it had never been played: the client must not re-read the round
 * once it is finished, or the result — and the share card with it — is wiped off the screen.
 */

test.beforeEach(async ({ page }) => {
    await skipTour(page);
});

test("opening the daily shows its state and starts no clock", async ({ page }) => {
    await page.goto("/?view=daily");

    await expect(page.locator("#daily-start-button")).toBeVisible();
    await expect(page.locator("#daily-timer")).toBeHidden();
    await expect(page.locator("#daily-actions")).toBeHidden();
    await expect(page.locator("#daily-number")).toHaveText(/^#\d+$/);
});

test("a finished daily stays on screen, with its share card", async ({ page }) => {
    await page.goto("/?view=daily");
    await page.locator("#daily-start-button").click();
    await expect(page.locator("#daily-actions")).toBeVisible();

    const verdict = await playToTheEnd(page, page.locator("#daily-long"), "/api/daily/guess");
    expect(verdict.sessionComplete).toBe(true);

    const done = page.locator("#daily-done");
    await expect(done).toBeVisible();
    await expect(page.locator("#daily-share")).toBeVisible();
    await expect(page.locator("#daily-done-line")).not.toBeEmpty();

    // The bug this guards against arrives a moment later, so give it that moment.
    const rereads = countRequests(page, /^\/api\/daily\/round$/);
    await page.waitForTimeout(2000);
    expect(rereads).toHaveLength(0);
    await expect(done).toBeVisible();
    await expect(page.locator("#daily-actions")).toBeHidden();
});

test("the share text gives nothing away", async ({ page, context, browserName }) => {
    test.skip(browserName !== "chromium", "clipboard permissions are Chromium's");
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.goto("/?view=daily");
    await page.locator("#daily-start-button").click();
    await playToTheEnd(page, page.locator("#daily-short"), "/api/daily/guess");

    await page.locator("#daily-share").click();
    const text = await page.evaluate(async () => {
        try { return await navigator.clipboard.readText(); } catch (e) { return null; }
    });
    // Phones take the share sheet instead of the clipboard; the text is then in the textarea.
    const shared = text || await page.locator("#daily-share-text").inputValue();
    expect(shared).toMatch(/#\d+/);
    // A date or a pair would be the answer: the chart is one everybody plays today.
    expect(shared).not.toMatch(/BTC|ETH|BNB|SOL|USDT/);
    expect(shared).not.toMatch(/\d{4}-\d{2}-\d{2}/);
});
