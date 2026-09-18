package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Through the whole filter chain: Spring Security stamps no-store on every response that does not
 * set its own Cache-Control, which is exactly what these two used to carry.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicCacheHeadersTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void theLibrariesAreKeptFiveMinutesAndServedStaleForADay() throws Exception {
        for (String kind : new String[] {"candle-pattern", "technical-pattern", "psychology"}) {
            mockMvc.perform(get("/api/content/" + kind))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=300, public, stale-while-revalidate=86400"))
                    .andExpect(header().doesNotExist("Pragma"));
        }
    }

    @Test
    void thePairListIsKeptFiveMinutesAndServedStaleForAnHour() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public, stale-while-revalidate=3600"));
    }

    @Test
    void anUnknownKindIsStillNotCached() throws Exception {
        mockMvc.perform(get("/api/content/blog"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
