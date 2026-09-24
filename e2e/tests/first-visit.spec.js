// @ts-check
const { test, expect, QUIET_MS, countRequests } = require("./helpers");

/*
 * A first visit gets the three-step tour, and the game's first chart waits for it. A tour laid
 * over a running round spent the newcomer's first guess while they read how to make one; and
 * ending the tour on the daily must not deal a practice chart behind the daily tab.
 */

test("the tour holds the first chart until the player asks to play", async ({ page }) => {
    const rounds = countRequests(page, /^\/api\/practice\/round$/);
    await page.goto("/");

    const tour = page.locator("#onboarding");
    await expect(tour).toBeVisible();
    await page.waitForTimeout(QUIET_MS);
    expect(rounds).toHaveLength(0);

    await page.locator("#onboarding-next").click();
    await page.locator("#onboarding-next").click();
    await page.locator("#onboarding-play").click();

    await expect(tour).toBeHidden();
    await expect(page.locator("#guess-timer")).toBeVisible();
    expect(rounds).toHaveLength(1);
});

test("ending the tour on the daily deals no practice chart behind it", async ({ page }) => {
    const rounds = countRequests(page, /^\/api\/practice\/round$/);
    await page.goto("/");

    await page.locator("#onboarding-next").click();
    await page.locator("#onboarding-next").click();
    await page.locator("#onboarding-daily").click();

    await expect(page.locator("#view-daily")).toBeVisible();
    await expect(page.locator("#daily-start-button")).toBeVisible();
    await page.waitForTimeout(QUIET_MS);
    expect(rounds).toHaveLength(0);
});

test("a returning visitor is not greeted as a stranger", async ({ page }) => {
    // A key the page wrote before the tour existed counts as having been here.
    await page.addInitScript(() => localStorage.setItem("candles-theme", "dark"));
    await page.goto("/");
    await expect(page.locator("#game-start-button")).toBeVisible();
    await expect(page.locator("#onboarding")).toBeHidden();
});

test("keys belong to the tour while it is up", async ({ page, isMobile }) => {
    test.skip(isMobile, "a phone has no keyboard shortcuts");
    const rounds = countRequests(page, /^\/api\/practice\/round$/);
    await page.goto("/");
    await expect(page.locator("#onboarding")).toBeVisible();

    // Space would press the start gate behind the dialog, and start a clock nobody is watching.
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    await page.keyboard.press("Space");
    await page.keyboard.press("ArrowUp");
    await page.waitForTimeout(500);
    expect(rounds).toHaveLength(0);
});
