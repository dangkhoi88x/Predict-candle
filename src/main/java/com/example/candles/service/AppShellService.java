package com.example.candles.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Serves {@code index.html} with its 34 scripts joined into one file and its two stylesheets into
 * another, each named by a hash of its contents so it can be cached for a year.
 *
 * <b>Why:</b> the page used to ask for 36 separate files, every one {@code no-cache}. The demo runs
 * on 0.1 of a CPU, so a first visit queued them — measured at 0.4-1.6s each where one alone takes
 * 0.23s — and every later visit still sent 36 revalidations to be told nothing had changed. A
 * content-hashed name is what lets the browser skip asking: a changed file is a new name.
 *
 * <b>The source files stay exactly as they are.</b> There is no build step and nothing generated is
 * committed; the bundle is read from the same {@code static/} files at runtime, in the order
 * {@code index.html} lists them, which is the order they were always executed in. Only local
 * {@code <script src>} and {@code <link rel="stylesheet">} tags are gathered — the inline theme
 * script in the head, and anything injected later (the wallet bundle, the editor), are untouched.
 *
 * Each script is wrapped in its own {@code try}. As separate files, one that threw at load stopped
 * only itself; joined without the wrapper it would stop every file after it. All of them are
 * IIFEs publishing one global, so the block changes nothing else about how they run.
 *
 * The stylesheet is served from the site root, never a subdirectory: {@code style.css} names its
 * fonts as {@code url("fonts/...")}, relative to wherever the stylesheet is.
 *
 * While running from exploded classes (a checkout) the files are re-read whenever one changes, so
 * {@code ./mvnw -q process-resources} still reaches a running server. Inside a jar nothing can
 * change and nothing is checked.
 */
@Service
public class AppShellService {

    static final String PAGE = "classpath:static/index.html";
    private static final String STATIC = "classpath:static/";

    private static final String SCRIPT_SLOT = "<!--app-shell:script-->";
    private static final String STYLESHEET_SLOT = "<!--app-shell:stylesheet-->";

    private static final Pattern SCRIPT = Pattern.compile(
            "<script src=\"([a-z0-9-]+\\.js)\"></script>[ \\t]*\\R?");
    private static final Pattern STYLESHEET = Pattern.compile(
            "<link rel=\"stylesheet\" href=\"([a-z0-9-]+\\.css)\"/>[ \\t]*\\R?");

    /** One served file: the bytes, their gzip, and the name/ETag they are addressed by. */
    public record Asset(String hash, byte[] body, byte[] gzipped) {
    }

    /** Everything built from one reading of the sources. */
    record Shell(Asset page, Asset script, Asset stylesheet, List<String> scripts,
                 List<String> stylesheets, long signature) {
    }

    private final ResourceLoader resources;
    private volatile Shell shell;

    public AppShellService(ResourceLoader resources) {
        this.resources = resources;
    }

    public Asset page() {
        return current().page();
    }

    /** The joined scripts. Compare {@link Asset#hash()} with the one asked for before caching it. */
    public Asset script() {
        return current().script();
    }

    /** The joined stylesheets. Compare {@link Asset#hash()} with the one asked for before caching it. */
    public Asset stylesheet() {
        return current().stylesheet();
    }

    List<String> scripts() {
        return current().scripts();
    }

    List<String> stylesheets() {
        return current().stylesheets();
    }

    private Shell current() {
        Shell built = shell;
        if (stale(built)) {
            synchronized (this) {
                built = shell;
                if (stale(built)) {
                    built = build();
                    shell = built;
                }
            }
        }
        return built;
    }

    private boolean stale(Shell built) {
        return built == null
                || (built.signature() != 0 && built.signature() != signature(built.scripts(), built.stylesheets()));
    }

    private Shell build() {
        String html = read(PAGE);

        List<String> scripts = new ArrayList<>();
        StringBuilder js = new StringBuilder();
        html = gather(html, SCRIPT, SCRIPT_SLOT, scripts);
        for (String name : scripts) {
            js.append("try {\n").append(read(STATIC + name))
                    .append("\n} catch (e) { console.error(\"").append(name).append("\", e); }\n");
        }

        List<String> stylesheets = new ArrayList<>();
        StringBuilder css = new StringBuilder();
        html = gather(html, STYLESHEET, STYLESHEET_SLOT, stylesheets);
        for (String name : stylesheets) {
            css.append(read(STATIC + name)).append('\n');
        }

        Asset script = asset(js.toString());
        Asset stylesheet = asset(css.toString());
        html = html.replace(STYLESHEET_SLOT,
                        "<link rel=\"stylesheet\" href=\"/app." + stylesheet.hash() + ".css\"/>\n")
                .replace(SCRIPT_SLOT, "<script src=\"/app." + script.hash() + ".js\"></script>\n");

        return new Shell(asset(html), script, stylesheet, List.copyOf(scripts), List.copyOf(stylesheets),
                signature(scripts, stylesheets));
    }


    /**
     * Removes every tag the pattern matches, collecting the file each names, and leaves one slot
     * where the last of them stood — the end of the body for scripts, so they still run after the
     * markup they reach for, and the head for the stylesheets.
     */
    private static String gather(String html, Pattern tag, String slot, List<String> names) {
        Matcher m = tag.matcher(html);
        StringBuilder out = new StringBuilder();
        int last = -1;
        while (m.find()) {
            names.add(m.group(1));
            m.appendReplacement(out, "");
            last = out.length();
        }
        m.appendTail(out);
        if (last < 0) throw new IllegalStateException("index.html has no " + tag.pattern());
        out.insert(last, slot);
        return out.toString();
    }

    /**
     * The last-modified times of every source, folded into one number — or 0 inside a jar, which is
     * what switches checking off.
     */
    private long signature(List<String> scripts, List<String> stylesheets) {
        try {
            Resource page = resources.getResource(PAGE);
            if (!page.isFile()) return 0;
            long sum = page.lastModified();
            for (String name : scripts) sum = sum * 31 + resources.getResource(STATIC + name).lastModified();
            for (String name : stylesheets) sum = sum * 31 + resources.getResource(STATIC + name).lastModified();
            return sum == 0 ? 1 : sum;
        } catch (IOException e) {
            return 1;
        }
    }

    private String read(String location) {
        try (var in = resources.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
    }

    private static Asset asset(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        return new Asset(hash(body), body, gzip(body));
    }

    private static String hash(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compressed once here, so a 0.1-CPU server is not gzipping the same bytes on every visit. */
    private static byte[] gzip(byte[] body) {
        var out = new ByteArrayOutputStream(body.length / 3);
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
