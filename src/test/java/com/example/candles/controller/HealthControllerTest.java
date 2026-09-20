package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Through the whole chain: this has to answer before a session exists and without one. */
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void itAnswersAnyoneWithoutSigningIn() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok\n"))
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"));
    }

    @Test
    void theAnswerIsNeverCached() throws Exception {
        // A cached one would say a sleeping instance is awake.
        mockMvc.perform(get("/healthz"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void itStaysSmallEnoughForTheKeepAliveCronToAccept() throws Exception {
        // cron-job.org fails a job whose response is too large, which is what switched the old
        // ping at / (77 KB of game page) off and left the demo asleep.
        MvcResult result = mockMvc.perform(get("/healthz")).andReturn();
        assertThat(result.getResponse().getContentAsByteArray().length).isLessThan(64);
    }
}
