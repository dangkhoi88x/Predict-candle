// @ts-check
const { test, expect, skipTour, playToTheEnd } = require("./helpers");

/*
 * A finished practice chart can be sent as a link. The score it advertises is one the server
 * signed, and the link opens on the daily's board in challenge mode — read on load, then
 * stripped from the address bar.
 */

test.beforeEach(async ({ page }) => {
    await skipTour(page);
});

test("a finished chart becomes a link a friend can play", async ({ page, context, browser }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.addInitScript(() => { delete window.navigator.share; });
    await page.goto("/");
    await page.locator("#game-start-button").click();

    const verdict = await playToTheEnd(page, page.locator("#guess-long"), "/api/practice/guess");
    expect(verdict.challengeToken).toBeTruthy();

    const offer = page.locator("#challenge-create");
    await expect(offer).toBeVisible();
    const [created] = await Promise.all([
        page.waitForResponse((r) => r.url().endsWith("/api/challenges") && r.request().method() === "POST"),
        offer.click(),
    ]);
    expect(created.status()).toBe(200);
    const { path } = await created.json();
    expect(path).toMatch(/^\/\?thach=/);

    // The friend: a separate browser, so nothing of the creator's session comes along.
    const friendContext = await browser.newContext();
    await friendContext.route((url) => url.hostname !== "localhost", (route) => route.abort());
    await friendContext.addInitScript(() => localStorage.setItem("candles-onboarded", "1"));
    const friend = await friendContext.newPage();
    await friend.goto(path);

    await expect(friend.locator("#view-daily")).toBeVisible();
    await expect(friend.locator("#daily-title")).toHaveText("Thách Đấu");
    await expect(friend.locator("#daily-start-button")).toBeVisible();
    await expect(friend).toHaveURL(/\/$/);

    await friend.locator("#daily-start-button").click();
    const played = await playToTheEnd(friend, friend.locator("#daily-short"), "/guess");
    expect(played.sessionComplete).toBe(true);
    await expect(friend.locator("#daily-done")).toBeVisible();
    await friendContext.close();
});
