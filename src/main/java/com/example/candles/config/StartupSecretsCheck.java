package com.example.candles.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to finish starting when a real deployment is still signing with the dev secrets.
 *
 * Both defaults are in application.yaml in plain sight, which is right for a checkout that has
 * to run with no setup — but they are also the signing keys for the access token and the round
 * token. Anyone who can read this repository could mint an admin session, or a round token
 * carrying whatever answer they liked, against a deployment that shipped without setting them.
 * That is not a configuration slip that should be discoverable from the outside, and until now
 * nothing said a word about it: the app started happily and looked correct.
 *
 * The profile is what separates the two cases. `application.yaml` sets `dev` as the default
 * profile, so a checkout, `./mvnw spring-boot:run` and the test suite all keep working
 * untouched. The Dockerfile sets `prod`, and the container is the only way this is deployed, so
 * a deployment that forgets the environment variables now fails loudly at boot instead of
 * running with a key that is public.
 */
@Component
public class StartupSecretsCheck {

    /** Kept as literals on purpose: the check has to notice when the yaml default is still in use. */
    static final String DEV_AUTH_SECRET = "dev-only-secret-change-me-0123456789abcdef0123456789abcdef";
    static final String DEV_ROUND_SECRET = "dev-only-secret-change-me-0123456789abcdef";

    private final String authSecret;
    private final String roundSecret;
    private final Environment environment;

    @Autowired
    public StartupSecretsCheck(AuthProperties auth, CandlesProperties candles, Environment environment) {
        this(auth.jwt().secret(), candles.jwt().secret(), environment);
    }

    /**
     * Takes the two values rather than the property objects so a test can hand it whatever it
     * likes. That is not only convenience: a developer machine here has a gitignored `.env`
     * supplying real secrets, so a test that read the injected properties would pass on CI and
     * fail locally — or the reverse — and prove nothing either way.
     */
    StartupSecretsCheck(String authSecret, String roundSecret, Environment environment) {
        this.authSecret = authSecret;
        this.roundSecret = roundSecret;
        this.environment = environment;
    }

    /*
     * @PostConstruct rather than ApplicationReadyEvent, and the difference is not cosmetic.
     * The ready event fires after the web server is already accepting connections, so the
     * first version of this class logged "Started CandlesApplication" and *then* threw —
     * leaving a window, however short, in which the app answered requests while signing with
     * a key published in this repository. Failing during context refresh means the port is
     * never bound.
     */
    @PostConstruct
    void refuseDevelopmentSecretsOutsideDevelopment() {
        List<String> unset = problems();
        if (unset.isEmpty() || isDevelopment()) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to run with development secrets outside the dev profile. "
                        + String.join(" ", unset)
                        + " These values are published in application.yaml, so anyone who can read "
                        + "the repository could forge sessions and round answers against this "
                        + "deployment. Set them to random values of at least 32 characters.");
    }

    /** Package-private so the test can assert on which of the two was caught. */
    List<String> problems() {
        List<String> found = new ArrayList<>();
        if (DEV_AUTH_SECRET.equals(authSecret)) {
            found.add("AUTH_JWT_SECRET is still the development default.");
        }
        if (DEV_ROUND_SECRET.equals(roundSecret)) {
            found.add("ROUND_TOKEN_SECRET is still the development default.");
        }
        return found;
    }

    /**
     * `matchesProfiles`, not `getActiveProfiles`. The two differ exactly where it matters here:
     * a checkout has no *active* profile at all — `spring.profiles.default: dev` supplies the
     * default — so `getActiveProfiles()` returns an empty array and reading it made every
     * ordinary run look like an unnamed deployment. The first version of this class did that
     * and turned the whole test suite red on CI, while passing locally, because a developer
     * machine has a gitignored `.env` with real secrets that stopped the check ever reaching
     * the profile question. `matchesProfiles` consults the default profiles too.
     */
    private boolean isDevelopment() {
        return environment.matchesProfiles("dev", "test");
    }
}
