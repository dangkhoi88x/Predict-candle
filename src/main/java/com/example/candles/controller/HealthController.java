package com.example.candles.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Two words, for the two things that ping this address: Render's health check and the cron job
 * that stops the free instance sleeping.
 *
 * <b>It deliberately touches nothing.</b> A health check that queried the database would make a
 * Neon compute waking from idle — an ordinary second — look like a dead instance, and Render
 * answers an unhealthy instance by restarting it, turning that second into a cold start of
 * minutes. What this endpoint claims is exactly what its callers need to know: the process is up
 * and serving HTTP. Whether an upstream is reachable is the ops pane's question, and it has a
 * better answer there ({@code RecentErrors}) than a boolean could give here.
 *
 * It replaced {@code /} in {@code render.yaml}: that path builds and sends the whole 77 KB game
 * page, which is a lot of a tenth of a CPU to spend on being asked whether the server is alive.
 * The keep-alive cron pings it for a second reason — cron-job.org fails a job whose response is
 * too large, which is how the old ping at {@code /} came to be switched off and the demo came to
 * be asleep whenever somebody opened it.
 *
 * {@code no-store} because a cached answer would say a sleeping instance is awake.
 */
@RestController
public class HealthController {

    private static final byte[] OK = "ok\n".getBytes(StandardCharsets.UTF_8);

    @GetMapping("/healthz")
    public ResponseEntity<byte[]> healthz() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(OK);
    }
}
