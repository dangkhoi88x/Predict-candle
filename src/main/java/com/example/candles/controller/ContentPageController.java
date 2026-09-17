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
import com.example.candles.service.BlogDocumentHtml;
import com.example.candles.service.BlogService;

/**
 * A page per blog post, rendered on the server.
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

    private final BlogService blog;
    private final String siteUrl;

    public ContentPageController(BlogService blog,
                                 @Value("${candles.site-url:https://candles-oj1q.onrender.com}") String siteUrl) {
        this.blog = blog;
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
                <footer><p><a href="/blog">Tất cả bài viết</a> · <a href="/">Chơi đoán nến</a></p></footer>
                </body></html>
                """.formatted(
                        BlogDocumentHtml.escape(title), BlogDocumentHtml.escape(description),
                        BlogDocumentHtml.escape(canonical), BlogDocumentHtml.escape(title),
                        BlogDocumentHtml.escape(description), BlogDocumentHtml.escape(canonical),
                        BlogDocumentHtml.escape(image == null ? siteUrl + "/og-image.png" : image),
                        STYLE, BlogDocumentHtml.escape(appHref), BlogDocumentHtml.escape(appLabel), body);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html; charset=UTF-8"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(head);
    }

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
