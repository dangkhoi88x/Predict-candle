// @ts-check
const { test, expect, skipTour } = require("./helpers");

/*
 * A shortcut presses the button a mouse would press. The handler ignores a keystroke whose
 * target is a button, so the case worth pinning is the one a player actually hits: start with
 * the mouse, then call with the arrows.
 */

test.beforeEach(async ({ page, isMobile }) => {
    test.skip(isMobile, "a phone has no arrow keys");
    await skipTour(page);
});

test("after starting with the mouse, the arrow keys make the call", async ({ page }) => {
    await page.goto("/");
    await page.locator("#game-start-button").click();
    await expect(page.locator("#guess-long")).toBeEnabled();
    await page.waitForTimeout(400);

    const [request] = await Promise.all([
        page.waitForRequest((r) => r.url().endsWith("/api/practice/guess") && r.method() === "POST"),
        page.keyboard.press("ArrowDown"),
    ]);
    expect(request.postDataJSON().direction).toBe("SHORT");
});

test("Space starts the chart on the game tab", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#game-start-button")).toBeVisible();
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    await page.keyboard.press("Space");
    await expect(page.locator("#guess-timer")).toBeVisible();
});
