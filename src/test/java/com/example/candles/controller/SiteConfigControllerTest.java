package com.example.candles.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SiteConfigControllerTest {

    @Test
    void aCodeBecomesItsGoatCounterEndpoint() {
        assertEquals("https://candle-guess.goatcounter.com/count",
                SiteConfigController.goatcounterEndpoint("candle-guess"));
        assertEquals("https://candle-guess.goatcounter.com/count",
                SiteConfigController.goatcounterEndpoint("  Candle-Guess "));
    }

    @Test
    void nothingConfiguredCountsNothing() {
        assertNull(SiteConfigController.goatcounterEndpoint(""));
        assertNull(SiteConfigController.goatcounterEndpoint(null));
    }

    @Test
    void anythingButACodeIsRefusedRatherThanLoadedFrom() {
        // A URL or a hostname in the variable must not become a script source.
        assertNull(SiteConfigController.goatcounterEndpoint("https://evil.example/count"));
        assertNull(SiteConfigController.goatcounterEndpoint("evil.example"));
        assertNull(SiteConfigController.goatcounterEndpoint("a/b"));
        assertNull(SiteConfigController.goatcounterEndpoint("-leading"));
    }
}
