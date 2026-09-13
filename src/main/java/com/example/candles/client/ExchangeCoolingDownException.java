package com.example.candles.client;

import org.springframework.web.client.RestClientException;
import java.time.Instant;

/**
 * Thrown instead of calling the exchange while it has told this server to back off.
 *
 * A RestClientException on purpose, so it takes the same path to the caller as the refusal it
 * stands in for: the handler that turns an upstream failure into a 502 needs no second branch.
 */
public class ExchangeCoolingDownException extends RestClientException {

    private final Instant until;

    public ExchangeCoolingDownException(Instant until) {
        super("Not calling the exchange until " + until + " — it answered the last request with a ban");
        this.until = until;
    }

    public Instant until() {
        return until;
    }
}
