// @ts-check
const { test, expect, skipTour } = require("./helpers");

/*
 * The crawlable pages (/mau-nen/<key>, /mau-hinh/<key>, /blog/<slug>) hand a reader over to the
 * app with a parameter, and the views they name are built from bundles fetched on first open.
 * `?card=` once opened the right tab and no card, because the link reached for a view that had
 * not been built yet; nothing on the Java side can see that.
 */

test.beforeEach(async ({ page }) => {
    await skipTour(page);
});

/** The last entry rather than the first, so the card has to be found, opened and scrolled to. */
async function lastKey(request, kind) {
    const items = await (await request.get(`/api/content/${kind}`)).json();
    expect(items.length).toBeGreaterThan(1);
    return items[items.length - 1].itemKey;
}

test("?card= opens a candlestick pattern's card", async ({ page, request }) => {
    const key = await lastKey(request, "candle-pattern");
    await page.goto(`/?view=patterns&card=${key}`);

    const card = page.locator(`#view-patterns .pattern-card[data-pattern="${key}"]`);
    await expect(card.locator(".pattern-detail")).toBeVisible();
    await expect(card).toBeInViewport();
    await expect(page).toHaveURL(/\/$/);
});

test("?card= opens a chart pattern's card", async ({ page, request }) => {
    const key = await lastKey(request, "technical-pattern");
    await page.goto(`/?view=technical&card=${key}`);

    const card = page.locator(`#view-technical .pattern-card[data-pattern="${key}"]`);
    await expect(card.locator(".pattern-detail")).toBeVisible();
    await expect(card).toBeInViewport();
    await expect(page).toHaveURL(/\/$/);
});

test("?post= opens a blog post in place", async ({ page, request }) => {
    const posts = await (await request.get("/api/blog/posts")).json();
    const slug = posts[posts.length - 1].slug;
    await page.goto(`/?view=blog&post=${slug}`);

    const post = page.locator(`#view-blog .blog-post[data-slug="${slug}"]`);
    await expect(post).toHaveClass(/expanded/);
    await expect(page).toHaveURL(/\/$/);
});

test("?view=profile is refused, since a link cannot know the visitor is signed in", async ({ page }) => {
    await page.goto("/?view=profile");
    await expect(page.locator("#view-game")).toBeVisible();
    await expect(page.locator("#view-profile")).toBeHidden();
});

test("the server-rendered pattern page links into the card it describes", async ({ page, request }) => {
    const key = await lastKey(request, "candle-pattern");
    await page.goto(`/mau-nen/${key}`);
    await page.locator(`a[href*="card=${key}"]`).first().click();

    await expect(page.locator(`#view-patterns .pattern-card[data-pattern="${key}"] .pattern-detail`)).toBeVisible();
});
