package com.example.candles.controller;

import com.example.candles.service.AppShellService;
import com.example.candles.service.AppShellService.Asset;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The game's page and the two files it loads everything through ({@link AppShellService}).
 *
 * The page is {@code no-cache} with an ETag, so a returning visitor pays one small revalidation
 * and a deploy is seen at once. The files it names are {@code immutable} for a year: their name
 * is their content's hash, so a changed file is a different address and nothing ever has to ask.
 *
 * A hash that is not the current one still gets today's file, but {@code no-cache}, so it is never
 * kept under a name that does not describe it. Render runs the old instance beside the new one
 * through a deploy, so a page from one can ask the other for its bundle; a 404 there would be a
 * page with no scripts at all, where the neighbouring version almost always just works.
 */
@RestController
public class AppShellController {

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final MediaType JAVASCRIPT = new MediaType("text", "javascript", StandardCharsets.UTF_8);
    private static final MediaType CSS = new MediaType("text", "css", StandardCharsets.UTF_8);
    private static final CacheControl FOREVER = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final AppShellService shell;

    public AppShellController(AppShellService shell) {
        this.shell = shell;
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<byte[]> page(@RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String accept) {
        return serve(shell.page(), HTML, CacheControl.noCache(), accept);
    }

    @GetMapping("/app.{hash}.js")
    public ResponseEntity<byte[]> script(@PathVariable String hash,
                                         @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String accept) {
        Asset script = shell.script();
        return serve(script, JAVASCRIPT, script.hash().equals(hash) ? FOREVER : CacheControl.noCache(), accept);
    }

    @GetMapping("/app.{hash}.css")
    public ResponseEntity<byte[]> stylesheet(@PathVariable String hash,
                                             @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String accept) {
        Asset stylesheet = shell.stylesheet();
        return serve(stylesheet, CSS, stylesheet.hash().equals(hash) ? FOREVER : CacheControl.noCache(), accept);
    }

    /**
     * A view's own bundle, fetched the first time that view is opened. An unknown view is a 404,
     * unlike an unknown hash: the hash moves with every edit, the name is written in index.html.
     */
    @GetMapping("/view-{view}.{hash}.js")
    public ResponseEntity<byte[]> chunk(@PathVariable String view, @PathVariable String hash,
                                        @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String accept) {
        Asset chunk = shell.chunk(view);
        if (chunk == null) return ResponseEntity.notFound().build();
        return serve(chunk, JAVASCRIPT, chunk.hash().equals(hash) ? FOREVER : CacheControl.noCache(), accept);
    }

    /**
     * Sends the copy compressed at startup when the client takes gzip; setting Content-Encoding is
     * also what stops Tomcat compressing it a second time. The two copies carry different ETags,
     * since a strong validator names one exact sequence of bytes.
     */
    private static ResponseEntity<byte[]> serve(Asset asset, MediaType type, CacheControl cache, String accept) {
        boolean gzip = gzipAccepted(accept);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(type)
                .cacheControl(cache)
                .eTag('"' + asset.hash() + (gzip ? "-gz" : "") + '"')
                .varyBy(HttpHeaders.ACCEPT_ENCODING);
        if (gzip) builder.header(HttpHeaders.CONTENT_ENCODING, "gzip");
        return builder.body(gzip ? asset.gzipped() : asset.body());
    }

    static boolean gzipAccepted(String accept) {
        return accept != null && accept.toLowerCase().contains("gzip");
    }
}
