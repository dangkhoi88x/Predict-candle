// @ts-check
const { test, expect, QUIET_MS, skipTour, countRequests, guess } = require("./helpers");

/*
 * A chart's clock starts when the server mints its token and cannot be paused, so dealing one
 * on page load spent the player's time before they had started: the demo's first six recorded
 * calls were all timeouts from one player who had not begun to play. Nothing deals a chart
 * unless the player asks for one.
 */

test.beforeEach(async ({ page }) => {
    await skipTour(page);
});

test("opening the page deals no chart", async ({ page }) => {
    const rounds = countRequests(page, /^\/api\/practice\/round$/);
    await page.goto("/");

    await expect(page.locator("#game-start-button")).toBeVisible();
    await page.waitForTimeout(QUIET_MS);
    expect(rounds).toHaveLength(0);
    await expect(page.locator("#guess-long")).toBeDisabled();
    await expect(page.locator("#guess-timer")).toBeHidden();
});

test("pressing start deals one chart, and a guess gets an answer", async ({ page }) => {
    const rounds = countRequests(page, /^\/api\/practice\/round$/);
    await page.goto("/");
    await page.locator("#game-start-button").click();

    await expect(page.locator("#game-start")).toBeHidden();
    await expect(page.locator("#guess-timer")).toBeVisible();
    await expect(page.locator("#chart svg")).toBeVisible();
    expect(rounds).toHaveLength(1);

    const verdict = await guess(page, page.locator("#guess-long"), "/api/practice/guess");
    expect(typeof verdict.correct).toBe("boolean");
    await expect(page.locator("#result-banner")).toBeVisible();
});

test("leaving the game tab mid-chart does not deal another one", async ({ page }) => {
    const rounds = countRequests(page, /^\/api\/practice\/round$/);
    await page.goto("/");
    await page.locator("#game-start-button").click();
    await expect(page.locator("#guess-timer")).toBeVisible();

    // Auto-advance stops when nobody is watching: switching view is one way of not watching.
    await page.evaluate(() => window.CandleNav.go("leaderboard"));
    await expect(page.locator("#view-leaderboard")).toBeVisible();
    await page.waitForTimeout(QUIET_MS);
    expect(rounds).toHaveLength(1);
});
