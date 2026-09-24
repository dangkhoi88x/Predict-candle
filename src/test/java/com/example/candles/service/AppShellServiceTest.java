package com.example.candles.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AppShellServiceTest {

    private final AppShellService shell = new AppShellService(new DefaultResourceLoader());

    private String page() {
        return new String(shell.page().body(), StandardCharsets.UTF_8);
    }

    /** What index.html itself lists, read straight off the source file. */
    private static List<String> listed(String pattern) throws IOException {
        String source = new String(new DefaultResourceLoader().getResource(AppShellService.PAGE)
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(pattern).matcher(source);
        return m.results().map(r -> r.group(1)).toList();
    }

    @Test
    void everyScriptIsJoinedInTheOrderThePageListedIt() throws IOException {
        // Order is the whole contract: pill.js, rolling.js and avatar.js define globals that later
        // files call at load time.
        List<String> listed = listed("<script src=\"([a-z0-9-]+\\.js)\"( data-view=\"[a-z-]+\")?></script>");
        assertThat(listed).hasSizeGreaterThan(30).startsWith("theme.js").endsWith("profile.js");
        assertThat(shell.scripts()).containsExactlyElementsOf(listed);

        // Within each bundle, the page's order survives.
        String upFront = new String(shell.script().body(), StandardCharsets.UTF_8);
        int previous = -1;
        for (String name : listed) {
            int at = upFront.indexOf("console.error(\"" + name + "\"");
            if (at < 0) continue; // in a view's own bundle, checked separately
            assertThat(at).as(name).isGreaterThan(previous);
            previous = at;
        }
    }

    @Test
    void thePageLoadsOneScriptAndOneStylesheetOfItsOwn() {
        String page = page();
        assertThat(Pattern.compile("<script defer ").matcher(page).results().count()).isEqualTo(1);
        assertThat(Pattern.compile("rel=\"stylesheet\"").matcher(page).results().count()).isEqualTo(1);
        // The inline theme script runs before first paint and must stay where it is.
        assertThat(page).contains("localStorage.getItem(\"candles-theme\")");
        assertThat(shell.stylesheets()).containsExactly("style.css", "play.css", "profile.css",
                "leaderboard.css", "heatmap.css", "content.css", "controls.css", "live.css",
                "onboarding.css", "daily.css", "trade.css", "candles-enhance.css");
    }

    @Test
    void theGamePageCarriesNoAdminRules() {
        // A third of the old stylesheet was the admin dashboard, sent to every player.
        assertThat(shell.stylesheets()).doesNotContain("admin.css");
        String css = new String(shell.stylesheet().body(), StandardCharsets.UTF_8);
        assertThat(css).doesNotContain(".admin-shell").doesNotContain(".adm-");
    }

    @Test
    void theStylesheetIsServedFromTheRootSoItsRelativeFontsStillResolve() {
        // style.css names its fonts as url("fonts/..."), resolved against the stylesheet's own path.
        String page = page();
        assertThat(page).contains("href=\"/app." + hashIn(page, "css") + ".css\"");
        String css = new String(shell.stylesheet().body(), StandardCharsets.UTF_8);
        assertThat(css).contains("url(\"fonts/inter-latin-wght-normal.woff2\")");
    }

    @Test
    void theScriptIsDeferredAndDeclaredInTheHeadWhereTheParserSeesItFirst() {
        // A plain tag stops the parser until 119 KB has downloaded and run: ~2.1s of the mobile
        // first paint. Deferred, it still runs after parsing, which is where it ran at the body's
        // end anyway, so every file still finds a finished DOM.
        String page = page();
        String head = page.substring(0, page.indexOf("</head>"));
        assertThat(head).contains("<script defer fetchpriority=\"high\" src=\"/app." + hashIn(page, "js") + ".js\"></script>");
        assertThat(page.substring(page.indexOf("</head>"))).doesNotContain("<script defer");
    }

    @Test
    void theDeferredScriptIsFetchedAtHighPriorityOrItWaitsForTheStylesheet() {
        // defer alone means Low priority, and Chrome holds those back until the render-blocking
        // CSS is in — the bundle that draws the first visit's LCP (the tour) was not even
        // requested until then. A/B on devtools throttling: LCP 2536 → 2241 ms.
        String head = page().substring(0, page().indexOf("</head>"));
        assertThat(head).containsPattern("<script defer fetchpriority=\"high\" src=\"/app\\.[0-9a-f]{12}\\.js\">");
    }

    @Test
    void theTwoFacesTheFirstPaintUsesArePreloaded() {
        // They are named inside the stylesheet, so without this the browser learns they exist
        // only after fetching and parsing 43 KB of CSS. crossorigin is required: a font is
        // fetched anonymously, and a preload without it is a second request, not the same one.
        String page = page();
        assertThat(page).contains("<link rel=\"preload\" href=\"/fonts/inter-latin-wght-normal.woff2\""
                + " as=\"font\" type=\"font/woff2\" crossorigin/>");
        assertThat(page).contains("/fonts/inter-vietnamese-wght-normal.woff2");
        // Only those two. A preload nothing uses promptly costs the bytes and earns a warning.
        assertThat(Pattern.compile("rel=\"preload\"").matcher(page).results().count()).isEqualTo(2);
    }

    @Test
    void theCommentsStayInTheSourceAndNeverReachTheBrowser() throws IOException {
        // 14.6 KB of the 76.6 KB, on the one file here that cannot be cached.
        String source = new String(new DefaultResourceLoader().getResource(AppShellService.PAGE)
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(source).contains("<!--");
        assertThat(page()).doesNotContain("<!--");
        assertThat(shell.page().body().length).isLessThan(source.length() - 10_000);
    }

    @Test
    void theStylesheetIsMinifiedAndStillSaysWhatItSaid() {
        String css = new String(shell.stylesheet().body(), StandardCharsets.UTF_8);
        assertThat(css).doesNotContain("/*");
        assertThat(css).contains("url(\"fonts/inter-latin-wght-normal.woff2\")");
        // The tokens every rule reads, and a selector whose meaning a careless minifier changes.
        assertThat(css).contains("--accent");
        assertThat(css).contains(":root{");
        assertThat(css.length()).isLessThan(140_000);
    }

    @Test
    void aLabelledScriptLeavesTheBundleThePageLoadsUpFront() {
        // The point of the split: somebody who plays and leaves never downloads these.
        String upFront = new String(shell.script().body(), StandardCharsets.UTF_8);
        assertThat(upFront).doesNotContain("console.error(\"demo-trade.js\"");
        assertThat(upFront).doesNotContain("console.error(\"profile.js\"");
        assertThat(upFront).doesNotContain("console.error(\"blog.js\"");
        // And what the game itself needs stays in it.
        assertThat(upFront).contains("console.error(\"app.js\"");
        assertThat(upFront).contains("console.error(\"nav.js\"");
        // patterns.js is deliberately unlabelled: the game names a pattern mid-round from it.
        assertThat(upFront).contains("console.error(\"patterns.js\"");
    }

    @Test
    void everyScriptLandsInExactlyOneBundle() {
        String upFront = new String(shell.script().body(), StandardCharsets.UTF_8);
        for (String name : shell.scripts()) {
            long bundles = shell.chunks().values().stream()
                    .filter(chunk -> new String(chunk.body(), StandardCharsets.UTF_8)
                            .contains("console.error(\"" + name + "\""))
                    .count();
            if (upFront.contains("console.error(\"" + name + "\"")) bundles++;
            assertThat(bundles).as(name).isEqualTo(1);
        }
    }

    @Test
    void aViewsScriptsAreJoinedInThePagesOrder() {
        String daily = new String(shell.chunk("daily").body(), StandardCharsets.UTF_8);
        assertThat(daily.indexOf("console.error(\"daily.js\""))
                .isLessThan(daily.indexOf("console.error(\"pattern-quiz.js\""))
                .isGreaterThan(-1);
        assertThat(shell.chunk("trade")).isNotNull();
        assertThat(shell.chunk("nosuchview")).isNull();
    }

    @Test
    void thePageCarriesTheListTheClientLoadsThemFrom() {
        // Written into the head rather than fetched: a manifest that arrives late is a tab that
        // opens empty and then fills.
        String page = page();
        shell.chunks().forEach((view, chunk) ->
                assertThat(page).contains("\"" + view + "\":\"" + AppShellService.chunkPath(view, chunk.hash()) + "\""));
        assertThat(page).contains("window.CandleChunks={");
    }

    @Test
    void thePageNamesTheFilesByTheirOwnHashes() {
        assertThat(hashIn(page(), "js")).isEqualTo(shell.script().hash());
        assertThat(hashIn(page(), "css")).isEqualTo(shell.stylesheet().hash());
    }

    @Test
    void theGzippedCopyIsTheSameBytes() throws IOException {
        AppShellService.Asset script = shell.script();
        try (var in = new GZIPInputStream(new ByteArrayInputStream(script.gzipped()))) {
            assertThat(in.readAllBytes()).isEqualTo(script.body());
        }
        assertThat(script.gzipped().length).isLessThan(script.body().length / 2);
    }

    private static String hashIn(String page, String extension) {
        Matcher m = Pattern.compile("/app\\.([0-9a-f]{12})\\." + extension).matcher(page);
        assertThat(m.find()).as("app.*." + extension + " in the page").isTrue();
        return m.group(1);
    }
}
