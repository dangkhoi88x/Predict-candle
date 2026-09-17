package com.example.candles.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.example.candles.dto.response.BlogPostDto;
import com.example.candles.dto.response.ContentItemDto;
import com.example.candles.entity.ContentKind;
import com.example.candles.service.BlogDocumentHtml;
import com.example.candles.service.BlogService;
import com.example.candles.service.ContentService;

/**
 * A page per blog post and per library entry, rendered on the server.
 *
 * <b>Why these exist at all:</b> every view of the app is a tab inside one {@code index.html} and a
 * post expands in place, so until now the whole site was a single URL. Nothing could be linked to,
 * quoted, or found in a search — and a crawler that runs no JavaScript saw an empty shell. These
 * pages are the crawlable copy: real HTML, one address per post, a canonical pointing at itself,
 * and a link into the app for a reader who wants the rest of the site.
 *
 * <b>They are not a second front end.</b> No app scripts, no wallet, no fonts, no styles beyond the
 * handful inlined below — a page that loaded the application to show one article would be slower
 * than the tab it is standing in for. The body comes from {@link BlogDocumentHtml}, which renders
 * the same stored document {@code blog-render.js} draws in the browser.
 *
 * Cached for ten minutes: posts change when an admin publishes one, and a crawler asking again
 * five minutes later is not a reason to walk the table.
 */
@RestController
public class ContentPageController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);
    private static final int DESCRIPTION_LENGTH = 160;

    /**
     * The three libraries, their addresses and the view each one hands a reader over to.
     *
     * Vietnamese paths on purpose: these are the URLs that end up in a search result and in
     * somebody's message, and the site is Vietnamese. The key in the path is the same
     * {@code item_key} the matcher in {@code PatternLibrary} is found by, so a page and the card
     * it links to cannot drift apart.
     */
    private enum Library {
        CANDLE("mau-nen", ContentKind.CANDLE_PATTERN, "patterns", "Mẫu nến",
                "Thư viện mẫu nến tiếng Việt: cách nhận biết từng mẫu và ý nghĩa của nó trên chart thật."),
        TECHNICAL("mau-hinh", ContentKind.TECHNICAL_PATTERN, "technical", "Mẫu hình giá",
                "Thư viện mẫu hình giá tiếng Việt: hai đáy, vai đầu vai, tam giác và cách nhận biết từng mẫu."),
        PSYCHOLOGY("tam-ly", ContentKind.PSYCHOLOGY, "psychology", "Tâm lý giao dịch",
                "Những ghi chú ngắn về tâm lý giao dịch: rủi ro, kỷ luật và các bẫy thường gặp.");

        final String path;
        final ContentKind kind;
        final String view;
        final String title;
        final String description;

        Library(String path, ContentKind kind, String view, String title, String description) {
            this.path = path;
            this.kind = kind;
            this.view = view;
            this.title = title;
            this.description = description;
        }

        static Library of(String path) {
            for (Library library : values()) {
                if (library.path.equals(path)) return library;
            }
            return null;
        }
    }

    private final BlogService blog;
    private final ContentService content;
    private final String siteUrl;

    public ContentPageController(BlogService blog, ContentService content,
                                 @Value("${candles.site-url:https://candles-oj1q.onrender.com}") String siteUrl) {
        this.blog = blog;
        this.content = content;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    @GetMapping(value = "/blog", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        List<BlogPostDto> posts = blog.published();
        StringBuilder body = new StringBuilder("<h1>Bài viết</h1>");
        body.append("<p class=\"lede\">Ghi chép về cách đọc chart và tâm lý giao dịch, từ Candle Guess.</p>");
        if (posts.isEmpty()) {
            body.append("<p>Chưa có bài viết nào.</p>");
        } else {
            body.append("<ul class=\"posts\">");
            for (BlogPostDto post : posts) {
                body.append("<li><a href=\"/blog/").append(BlogDocumentHtml.escape(post.slug())).append("\">")
                        .append(BlogDocumentHtml.escape(post.title())).append("</a>")
                        .append("<span class=\"meta\"> · ").append(DAY.format(post.createdAt())).append("</span>")
                        .append("<p>").append(BlogDocumentHtml.escape(BlogDocumentHtml.excerpt(post.body(), 140)))
                        .append("</p></li>");
            }
            body.append("</ul>");
        }
        return page("Bài viết — Candle Guess",
                "Ghi chép về cách đọc chart nến và tâm lý giao dịch, tiếng Việt, từ Candle Guess.",
                siteUrl + "/blog", null, body.toString(), "/?view=blog", "Mở trong ứng dụng");
    }

    @GetMapping(value = "/blog/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> post(@PathVariable String slug) {
        BlogPostDto post = blog.published().stream()
                .filter(p -> p.slug().equals(slug))
                .findFirst()
                .orElse(null);
        // An unpublished post is not "not ready to show", it is not there: the draft and the
        // typo'd address have to answer the same way, or this becomes a list of what is coming.
        if (post == null) return ResponseEntity.notFound().build();

        JsonNode body = post.body();
        String article = "<h1>" + BlogDocumentHtml.escape(post.title()) + "</h1>"
                + "<p class=\"meta\">" + DAY.format(post.createdAt())
                + (post.source() == null ? "" : " · nguồn: " + BlogDocumentHtml.escape(post.source()))
                + "</p>"
                + BlogDocumentHtml.render(body);

        return page(post.title() + " — Candle Guess",
                BlogDocumentHtml.excerpt(body, DESCRIPTION_LENGTH),
                siteUrl + "/blog/" + slug,
                post.coverImg(),
                article,
                "/?view=blog&post=" + slug,
                "Đọc trong ứng dụng");
    }

    /** One library — every entry listed, because a list of thirteen is the page worth crawling. */
    @GetMapping(value = "/{library:mau-nen|mau-hinh|tam-ly}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> library(@PathVariable String library) {
        Library lib = Library.of(library);
        StringBuilder body = new StringBuilder("<h1>").append(lib.title).append("</h1>")
                .append("<p class=\"lede\">").append(lib.description).append("</p><ul class=\"posts\">");
        for (ContentItemDto item : content.published(lib.kind)) {
            body.append("<li><a href=\"/").append(lib.path).append('/')
                    .append(BlogDocumentHtml.escape(item.itemKey())).append("\">")
                    .append(BlogDocumentHtml.escape(name(item))).append("</a><p>")
                    .append(BlogDocumentHtml.escape(summary(item))).append("</p></li>");
        }
        body.append("</ul>");
        return page(lib.title + " — Candle Guess", lib.description, siteUrl + "/" + lib.path, null,
                body.toString(), "/?view=" + lib.view, "Mở trong ứng dụng");
    }

    /** One entry: what it is, how to recognise it, and the card it opens in the app. */
    @GetMapping(value = "/{library:mau-nen|mau-hinh|tam-ly}/{key}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> entry(@PathVariable String library, @PathVariable String key) {
        Library lib = Library.of(library);
        ContentItemDto item = content.published(lib.kind).stream()
                .filter(i -> i.itemKey().equals(key))
                .findFirst()
                .orElse(null);
        if (item == null) return ResponseEntity.notFound().build();

        JsonNode body = item.body();
        StringBuilder html = new StringBuilder("<h1>").append(BlogDocumentHtml.escape(name(item))).append("</h1>");
        html.append("<p class=\"meta\">").append(lib.title);
        for (JsonNode tag : body.path("tags")) {
            html.append(" · ").append(BlogDocumentHtml.escape(tag.asString("")));
        }
        html.append("</p>");

        String summary = summary(item);
        if (!summary.isBlank()) html.append("<p>").append(BlogDocumentHtml.escape(summary)).append("</p>");
        if (body.path("howTo").isArray() && !body.path("howTo").isEmpty()) {
            html.append("<h2>Cách nhận biết</h2><ul>");
            for (JsonNode step : body.path("howTo")) {
                html.append("<li>").append(BlogDocumentHtml.escape(step.asString(""))).append("</li>");
            }
            html.append("</ul>");
        }

        // The card itself draws the shape; a page of words cannot, and pretending otherwise with a
        // picture of one pattern standing for another would be worse than sending the reader on.
        String appHref = lib.kind == ContentKind.PSYCHOLOGY
                ? "/?view=" + lib.view
                : "/?view=" + lib.view + "&card=" + item.itemKey();
        return page(name(item) + " — " + lib.title + " — Candle Guess",
                summary.isBlank() ? lib.description : summary,
                siteUrl + "/" + lib.path + "/" + item.itemKey(), null, html.toString(),
                appHref, "Xem hình trong ứng dụng");
    }

    /** Psychology notes keep their words in {@code body}; a pattern keeps its own in {@code summary}. */
    private static String summary(ContentItemDto item) {
        JsonNode body = item.body();
        return body.path("summary").asString(body.path("body").asString(""));
    }

    private static String name(ContentItemDto item) {
        JsonNode body = item.body();
        return body.path("name").asString(body.path("title").asString(item.title()));
    }

    /** Only the addresses that are real pages: the app's own, the blog index, and one per post. */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
                .append(url(siteUrl + "/", null))
                .append(url(siteUrl + "/blog", null));
        for (BlogPostDto post : blog.published()) {
            xml.append(url(siteUrl + "/blog/" + post.slug(), post.updatedAt() == null ? null : post.updatedAt().toString()));
        }
        for (Library library : Library.values()) {
            xml.append(url(siteUrl + "/" + library.path, null));
            for (ContentItemDto item : content.published(library.kind)) {
                xml.append(url(siteUrl + "/" + library.path + "/" + item.itemKey(),
                        item.updatedAt() == null ? null : item.updatedAt().toString()));
            }
        }
        xml.append("</urlset>\n");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(xml.toString());
    }

    private String url(String loc, String lastmod) {
        return "  <url><loc>" + BlogDocumentHtml.escape(loc) + "</loc>"
                + (lastmod == null ? "" : "<lastmod>" + BlogDocumentHtml.escape(lastmod) + "</lastmod>")
                + "</url>\n";
    }

    /**
     * The page shell. Styles are inlined and tiny on purpose — linking the app's stylesheet would
     * pull 200 KB of an interface this page does not have, and a crawler's render budget is not
     * spent on something a reader can see faster without.
     */
    private ResponseEntity<String> page(String title, String description, String canonical,
                                        String image, String body, String appHref, String appLabel) {
        String head = """
                <!doctype html>
                <html lang="vi"><head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                <title>%s</title>
                <meta name="description" content="%s"/>
                <link rel="canonical" href="%s"/>
                <meta property="og:type" content="article"/>
                <meta property="og:site_name" content="Candle Guess"/>
                <meta property="og:locale" content="vi_VN"/>
                <meta property="og:title" content="%s"/>
                <meta property="og:description" content="%s"/>
                <meta property="og:url" content="%s"/>
                <meta property="og:image" content="%s"/>
                <link rel="icon" href="/favicon.svg"/>
                <style>%s</style>
                </head><body>
                <header><a class="brand" href="/">Candle Guess</a><a class="app" href="%s">%s</a></header>
                <main>%s</main>
                <footer><p>%s</p></footer>
                </body></html>
                """.formatted(
                        BlogDocumentHtml.escape(title), BlogDocumentHtml.escape(description),
                        BlogDocumentHtml.escape(canonical), BlogDocumentHtml.escape(title),
                        BlogDocumentHtml.escape(description), BlogDocumentHtml.escape(canonical),
                        BlogDocumentHtml.escape(image == null ? siteUrl + "/og-image.png" : image),
                        STYLE, BlogDocumentHtml.escape(appHref), BlogDocumentHtml.escape(appLabel), body,
                        FOOTER_LINKS);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html; charset=UTF-8"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(head);
    }

    /**
     * Every section links to every other one. A sitemap tells a crawler what exists; links are how
     * it walks there, and these pages are otherwise reachable only from a file it has to be told
     * about.
     */
    private static final String FOOTER_LINKS =
            "<a href=\"/blog\">Bài viết</a> · <a href=\"/mau-nen\">Mẫu nến</a>"
            + " · <a href=\"/mau-hinh\">Mẫu hình giá</a> · <a href=\"/tam-ly\">Tâm lý giao dịch</a>"
            + " · <a href=\"/\">Chơi đoán nến</a>";

    private static final String STYLE = """
            :root { color-scheme: light dark; --bg:#fff; --text:#16161a; --muted:#61636c; --line:#e6e6ea; --accent:#2a63d6; }
            @media (prefers-color-scheme: dark) { :root { --bg:#0e0e11; --text:#ececf1; --muted:#9a9aa4; --line:#26262c; --accent:#7ba6ff; } }
            * { box-sizing: border-box; }
            body { margin:0; background:var(--bg); color:var(--text); font:16px/1.7 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
            header, main, footer { max-width: 42rem; margin: 0 auto; padding: 0 20px; }
            header { display:flex; align-items:center; justify-content:space-between; gap:16px; padding-top:22px; padding-bottom:22px; }
            .brand { font-weight:700; text-decoration:none; color:var(--text); }
            .app { text-decoration:none; color:var(--bg); background:var(--accent); padding:8px 14px; border-radius:999px; font-size:14px; }
            h1 { font-size:1.75rem; line-height:1.25; margin:0 0 8px; }
            h2 { font-size:1.3rem; margin:32px 0 8px; }
            h3 { font-size:1.1rem; margin:24px 0 8px; }
            .meta, .lede { color:var(--muted); }
            .meta { font-size:14px; margin:0 0 28px; }
            a { color:var(--accent); }
            img { max-width:100%; height:auto; border-radius:10px; }
            figure { margin:24px 0; }
            blockquote { margin:24px 0; padding-left:16px; border-left:3px solid var(--line); color:var(--muted); }
            pre { overflow-x:auto; padding:14px; border:1px solid var(--line); border-radius:10px; }
            ul.posts { list-style:none; padding:0; }
            ul.posts li { border-top:1px solid var(--line); padding:18px 0; }
            ul.posts p { color:var(--muted); margin:6px 0 0; }
            footer { border-top:1px solid var(--line); margin-top:48px; padding-top:18px; padding-bottom:40px; color:var(--muted); font-size:14px; }
            """;
}
