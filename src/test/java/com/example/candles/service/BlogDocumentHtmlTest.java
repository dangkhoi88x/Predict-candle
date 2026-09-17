package com.example.candles.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's copy of {@code blog-render.js}, which is the whole risk: three renderers read one
 * document, and the two public ones have to accept the same things and refuse the same things.
 * Escaping is this one's alone — it builds a string, where the browser's builds DOM nodes.
 */
class BlogDocumentHtmlTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode doc(String json) {
        return mapper.readTree(json);
    }

    @Test
    void theNodesTheEditorEmitsBecomeTheTagsTheReaderSees() {
        String html = BlogDocumentHtml.render(doc("""
                {"type":"doc","content":[
                  {"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"Mẫu nến"}]},
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Nến "},
                    {"type":"text","marks":[{"type":"bold"}],"text":"hammer"},
                    {"type":"text","text":" ở đáy."}]},
                  {"type":"bulletList","content":[{"type":"listItem","content":[
                    {"type":"paragraph","content":[{"type":"text","text":"Thân nhỏ"}]}]}]},
                  {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"Trích"}]}]},
                  {"type":"codeBlock","content":[{"type":"text","text":"if (x) {}"}]},
                  {"type":"horizontalRule"}]}
                """));

        assertThat(html)
                .contains("<h3>Mẫu nến</h3>")
                .contains("<p>Nến <strong>hammer</strong> ở đáy.</p>")
                .contains("<ul><li><p>Thân nhỏ</p></li></ul>")
                .contains("<blockquote><p>Trích</p></blockquote>")
                .contains("<pre><code>if (x) {}</code></pre>")
                .contains("<hr/>");
    }

    /** A heading starts at h2: h1 belongs to the post's title, the same rule blog-render.js keeps. */
    @Test
    void headingLevelsStayUnderTheTitle() {
        assertThat(BlogDocumentHtml.render(doc("""
                {"type":"doc","content":[{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"A"}]}]}
                """))).contains("<h2>A</h2>");
    }

    @Test
    void onlyHttpLinksAndImagesSurvive() {
        String html = BlogDocumentHtml.render(doc("""
                {"type":"doc","content":[
                  {"type":"paragraph","content":[
                    {"type":"text","marks":[{"type":"link","attrs":{"href":"https://example.com"}}],"text":"tốt"},
                    {"type":"text","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}],"text":"xấu"}]},
                  {"type":"image","attrs":{"src":"javascript:alert(1)","alt":"x"}},
                  {"type":"image","attrs":{"src":"https://cdn.example/a.png","alt":"ảnh","width":800,"height":450}}]}
                """));

        assertThat(html).contains("<a href=\"https://example.com\" rel=\"noopener noreferrer\">tốt</a>");
        // The link is dropped, the words are not: a link nobody will follow is still text.
        assertThat(html).contains("xấu").doesNotContain("javascript:");
        assertThat(html).contains("<img src=\"https://cdn.example/a.png\" alt=\"ảnh\" width=\"800\" height=\"450\"");
    }

    @Test
    void everythingWrittenOutIsEscaped() {
        String html = BlogDocumentHtml.render(doc("""
                {"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"<script>alert('x')</script> & co"}]}]}
                """));

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("&amp; co");
        assertThat(BlogDocumentHtml.escape("\"quoted\"")).isEqualTo("&quot;quoted&quot;");
    }

    @Test
    void anUnknownNodeKeepsItsTextAndTheOldFlatShapeStillRenders() {
        assertThat(BlogDocumentHtml.render(doc("""
                {"type":"doc","content":[{"type":"somethingNew","content":[{"type":"text","text":"vẫn đọc được"}]}]}
                """))).isEqualTo("<p>vẫn đọc được</p>");

        // V12 turned the seeded posts into documents; a database that has not run it holds this.
        assertThat(BlogDocumentHtml.render(doc("""
                [{"type":"text","text":"đoạn cũ"},{"type":"image","src":"https://cdn.example/b.png","alt":"b"}]
                """))).contains("<p>đoạn cũ</p>").contains("https://cdn.example/b.png");
    }

    @Test
    void theExcerptIsTheDocumentsOwnWordsCutOnASpace() {
        JsonNode body = doc("""
                {"type":"doc","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"Một câu ngắn."}]},
                  {"type":"paragraph","content":[{"type":"text","text":"Câu thứ hai dài hơn nhiều."}]}]}
                """);

        assertThat(BlogDocumentHtml.excerpt(body, 200)).isEqualTo("Một câu ngắn. Câu thứ hai dài hơn nhiều.");
        String short_ = BlogDocumentHtml.excerpt(body, 18);
        assertThat(short_).endsWith("…").doesNotContain("  ");
        assertThat(short_.length()).isLessThanOrEqualTo(19);
        assertThat(BlogDocumentHtml.excerpt(null, 50)).isEmpty();
    }
}
