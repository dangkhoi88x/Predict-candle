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
        List<String> listed = listed("<script src=\"([a-z0-9-]+\\.js)\"></script>");
        assertThat(listed).hasSizeGreaterThan(30).startsWith("theme.js").endsWith("profile.js");
        assertThat(shell.scripts()).containsExactlyElementsOf(listed);

        String bundle = new String(shell.script().body(),
                StandardCharsets.UTF_8);
        int previous = -1;
        for (String name : listed) {
            int at = bundle.indexOf("console.error(\"" + name + "\"");
            assertThat(at).as(name).isGreaterThan(previous);
            previous = at;
        }
    }

    @Test
    void thePageLoadsOneScriptAndOneStylesheetOfItsOwn() {
        String page = page();
        assertThat(Pattern.compile("<script src=").matcher(page).results().count()).isEqualTo(1);
        assertThat(Pattern.compile("rel=\"stylesheet\"").matcher(page).results().count()).isEqualTo(1);
        assertThat(page).contains("<script src=\"/app." + hashIn(page, "js") + ".js\"></script>\n</body>");
        // The inline theme script runs before first paint and must stay where it is.
        assertThat(page).contains("localStorage.getItem(\"candles-theme\")");
        assertThat(shell.stylesheets()).containsExactly("style.css", "candles-enhance.css");
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
