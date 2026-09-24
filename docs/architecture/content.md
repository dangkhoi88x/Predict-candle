# Content, patterns and heatmap

The pattern libraries, the heatmap and ticker, and the server-rendered pages crawlers read.

## Patterns and heatmap

`pattern/` holds two libraries — candlestick (`PatternLibrary`) and chart-shape
(`TechnicalPatternLibrary`, which uses `SwingPivotDetector`). Cards render hand-drawn SVG
illustrations; "Tìm ví dụ thật" calls `/api/{patterns,technical-patterns}/{id}/example` to
scan real stored history for a genuine occurrence.

Heatmap has two sources behind one view: crypto (CoinGecko, called straight from the browser)
and S&P 500 (`/api/market/sp500` → `YahooFinanceClient`). `treemap.js` does the layout for both.

**The ticker and the crypto heatmap drop stablecoins and derivative copies** through
`CandleCoins.tradable` (`coins.js`, loaded before both). CoinGecko's market-cap order is a third
dollar-pegged products on any given day, each at $1.00 and 0.00%, so both ask for more rows than
they show (40 → 14, 50 → 24). Three tests: a known stablecoin symbol, a name saying wrapped /
staked / bridged / tokenised, or a price within 5% of a dollar that moved under 0.5% or reports no 24h change —
the last catches pegged products whose names say nothing.

**Nothing above the views appears late.** The ticker and the live banner ship visible and hide
only on failure; appearing after their fetches pushed the whole shell down on every load (CLS
0.10 on a phone, where the banner has a row of its own). For the same reason the banner no longer
vanishes during the eight locked minutes of each hour — it says when the next round opens.

## Crawlable content pages

`GET /blog`, `GET /blog/{slug}` and a page per library entry — `/mau-nen/{key}`, `/mau-hinh/{key}`,
`/tam-ly/{key}`, each with its own index — are server-rendered HTML, plus `/sitemap.xml`
(`ContentPageController`). Every view of the app is a tab inside one `index.html` and a post expands
in place, so until these existed the whole site was one URL: nothing could be linked to, quoted or
found in a search, and a crawler that runs no JavaScript saw an empty shell.

- **They are not a second front end.** No app scripts, no wallet, no fonts, no stylesheet — a page
  that loaded the application to show one article would be slower than the tab it stands in for.
  The shell is ~30 lines of inlined CSS that follow `prefers-color-scheme`.
- **A draft answers exactly like a typo** — 404. Anything else turns the address space into a list
  of what is coming.
- Each page is canonical to itself, takes its `description` from the post's own opening words, and
  links into the app as `/?view=blog&post=<slug>`; `blog.js` opens that post and strips the
  parameter, the same idiom `?thach=` and `?view=` already use.
- `robots.txt` names the sitemap; the sitemap lists `/`, every index and one entry per published
  post and library entry with its `updatedAt`. Every page's footer links to every section, because
  a sitemap says what exists and links are how a crawler walks there.
- **The library paths are Vietnamese and the key in the path is the `item_key`** a matcher in
  `PatternLibrary` is found by, so a page and the card it links to cannot drift apart. A key that
  belongs to the *other* library is a 404 rather than an answer: two addresses for one card is what
  a canonical exists to prevent.
- A pattern page is words only — the card draws the shape, so the page hands the reader to it with
  `/?view=patterns&card=<key>`. Both libraries read that parameter at load (nav.js strips `view`
  first), each answers only for a key it holds, and whichever answers strips it. A psychology note
  has no shape, so it opens its tab and nothing else.

**`BlogDocumentHtml` is the third renderer of one document, and that is the cost worth naming.**
Tiptap produces it, `blog-render.js` draws it for a reader, and this writes it for a crawler. A node
the editor can emit needs a branch in all three, and the two public ones deliberately accept and
refuse the same things: same nodes, same six marks, same http(s)-only check on an href or an image
src, same fallback of an unknown node to its own text. Storing rendered HTML at publish time was the
alternative and is a second copy free to drift; a pure function cannot drift, it can only be
incomplete, and incomplete is visible. Escaping lives here alone — this builds a string where the
browser's renderer builds DOM nodes.
