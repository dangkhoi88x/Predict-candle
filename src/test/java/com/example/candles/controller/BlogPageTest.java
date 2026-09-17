package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import com.example.candles.dto.request.BlogPostRequest;
import com.example.candles.dto.response.BlogPostDto;
import com.example.candles.service.BlogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The crawlable copy of a post: one address, real HTML, no app.
 *
 * Until these existed the whole site was a single URL, so nothing could be linked to or found in a
 * search. What has to hold is that a page exists exactly for a published post, that it says where
 * it lives (canonical) and that the sitemap and the page agree about which posts those are.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BlogPageTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BlogService blog;

    private final ObjectMapper mapper = new ObjectMapper();

    private BlogPostDto post(String title, boolean published) {
        return blog.create(new BlogPostRequest(
                "bai-" + UUID.randomUUID().toString().substring(0, 8),
                title,
                mapper.readTree("[\"nến\"]"),
                "Nguồn thử", "https://example.com", null, null, null,
                mapper.readTree("""
                        {"type":"doc","content":[{"type":"paragraph","content":[
                          {"type":"text","text":"Thân nến nhỏ nằm ở đáy một nhịp giảm."}]}]}
                        """),
                published, null));
    }

    @Test
    void aPublishedPostHasItsOwnPageWithItsOwnWordsInIt() throws Exception {
        BlogPostDto published = post("Cách đọc nến hammer", true);

        String html = mockMvc.perform(get("/blog/" + published.slug()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("<title>Cách đọc nến hammer — Candle Guess</title>")
                .contains("<h1>Cách đọc nến hammer</h1>")
                .contains("Thân nến nhỏ nằm ở đáy một nhịp giảm.")
                .contains("<link rel=\"canonical\" href=\"https://candles-oj1q.onrender.com/blog/" + published.slug() + "\"/>")
                // The description is the post's own opening, not the site's boilerplate.
                .contains("<meta name=\"description\" content=\"Thân nến nhỏ")
                // And a way back into the app, landing on this post rather than the top of the list.
                .contains("/?view=blog&amp;post=" + published.slug());
    }

    /** A draft and a typo have to answer the same way, or this becomes a list of what is coming. */
    @Test
    void aDraftAndAnUnknownSlugAreBothAbsent() throws Exception {
        BlogPostDto draft = post("Bản nháp", false);

        mockMvc.perform(get("/blog/" + draft.slug())).andExpect(status().isNotFound());
        mockMvc.perform(get("/blog/khong-co-bai-nay")).andExpect(status().isNotFound());
    }

    @Test
    void theIndexAndTheSitemapListTheSamePublishedPosts() throws Exception {
        BlogPostDto published = post("Bài có thật", true);
        BlogPostDto draft = post("Bài nháp", false);

        String index = mockMvc.perform(get("/blog")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(index).contains("/blog/" + published.slug()).doesNotContain(draft.slug());

        String sitemap = mockMvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(sitemap)
                .contains("<loc>https://candles-oj1q.onrender.com/</loc>")
                .contains("<loc>https://candles-oj1q.onrender.com/blog</loc>")
                .contains("<loc>https://candles-oj1q.onrender.com/blog/" + published.slug() + "</loc>")
                .doesNotContain(draft.slug());
    }

    /** A title is somebody's own text, and it lands in a page title, an og tag and an h1. */
    @Test
    void aTitleThatLooksLikeMarkupIsEscapedEverywhereItIsWritten() throws Exception {
        BlogPostDto published = post("<script>alert('x')</script>", true);

        String html = mockMvc.perform(get("/blog/" + published.slug()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;");
    }

    /**
     * The libraries get the same treatment, and for the same reason: thirteen candlestick patterns
     * written in Vietnamese are the most searched-for text on the site and lived at no address.
     */
    @Test
    void everyLibraryEntryHasItsOwnPageAndItsOwnWordsOnIt() throws Exception {
        String html = mockMvc.perform(get("/mau-nen/doji"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .contains("<h1>Doji</h1>")
                .contains("<h2>Cách nhận biết</h2>")
                .contains("<link rel=\"canonical\" href=\"https://candles-oj1q.onrender.com/mau-nen/doji\"/>")
                // The card is where the shape is drawn, so the page sends the reader to it by key.
                .contains("/?view=patterns&amp;card=doji");

        assertThat(mockMvc.perform(get("/mau-hinh/double-bottom")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("/?view=technical&amp;card=double-bottom");

        // A psychology note has no shape to point at, so it opens its own tab and nothing else.
        String psychology = mockMvc.perform(get("/tam-ly")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(psychology).contains("/tam-ly/").contains("/?view=psychology");
    }

    @Test
    void anEntryThatIsNotInThatLibraryIsAbsentAndTheSitemapListsTheOnesThatAre() throws Exception {
        // The key exists — in the other library. Answering anyway would make two addresses for one
        // card, which is exactly what a canonical is supposed to prevent.
        mockMvc.perform(get("/mau-hinh/doji")).andExpect(status().isNotFound());
        mockMvc.perform(get("/mau-nen/khong-co-mau-nay")).andExpect(status().isNotFound());

        String sitemap = mockMvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(sitemap)
                .contains("<loc>https://candles-oj1q.onrender.com/mau-nen</loc>")
                .contains("<loc>https://candles-oj1q.onrender.com/mau-nen/doji</loc>")
                .contains("<loc>https://candles-oj1q.onrender.com/mau-hinh/double-bottom</loc>")
                .contains("<loc>https://candles-oj1q.onrender.com/tam-ly</loc>");
    }
}
