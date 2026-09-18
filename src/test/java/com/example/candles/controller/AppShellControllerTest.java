package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Through the whole filter chain on purpose: Spring Security writes its own no-store Cache-Control
 * onto responses, and the point of these files is the header that has to survive it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppShellControllerTest {

    @Autowired private MockMvc mockMvc;

    private String script() throws Exception {
        String page = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("/app\\.[0-9a-f]{12}\\.js").matcher(page);
        assertThat(m.find()).isTrue();
        return m.group();
    }

    @Test
    void thePageIsRevalidatedAndWhatItNamesIsCachedForAYear() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"));

        mockMvc.perform(get(script()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(header().string("Content-Type", "text/javascript;charset=UTF-8"));
    }

    @Test
    void anUnchangedPageCostsA304() throws Exception {
        MvcResult first = mockMvc.perform(get("/")).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mockMvc.perform(get("/").header("If-None-Match", etag)).andExpect(status().isNotModified());
    }

    @Test
    void anotherHashGetsTodaysFileButNeverKeepsItUnderThatName() throws Exception {
        // Mid-deploy, a page from one instance can ask the other for its bundle.
        mockMvc.perform(get("/app.000000000000.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
        mockMvc.perform(get("/app.000000000000.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void aClientThatTakesGzipGetsTheCopyCompressedAtStartup() throws Exception {
        String path = script();
        MvcResult plain = mockMvc.perform(get(path)).andReturn();
        MvcResult gzip = mockMvc.perform(get(path).header("Accept-Encoding", "gzip, br")).andReturn();

        assertThat(gzip.getResponse().getHeader("Content-Encoding")).isEqualTo("gzip");
        assertThat(plain.getResponse().getHeader("Content-Encoding")).isNull();
        assertThat(gzip.getResponse().getContentAsByteArray().length)
                .isLessThan(plain.getResponse().getContentAsByteArray().length);
        assertThat(gzip.getResponse().getHeader("ETag")).isNotEqualTo(plain.getResponse().getHeader("ETag"));
        assertThat(gzip.getResponse().getHeaders("Vary")).anyMatch(v -> v.contains("Accept-Encoding"));
    }
}
