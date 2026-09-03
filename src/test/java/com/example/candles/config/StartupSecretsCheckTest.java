package com.example.candles.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard that stops the published signing keys reaching a deployment.
 *
 * Both halves matter and only one is obvious. Refusing a production boot is the point of the
 * class; staying quiet on a developer machine is what stops the next person deleting it the
 * first time it gets in their way.
 *
 * Nothing here reads the injected properties, and that is deliberate. A developer machine in
 * this project has a gitignored `.env` supplying real secrets while CI runs on the checked-in
 * defaults, so a test that asked the running application what its secret was would pass in one
 * place and fail in the other while proving nothing in either. The values are handed in.
 */
class StartupSecretsCheckTest {

    private static final String REAL = "a-secret-nobody-published-0123456789abcdef";

    private static StartupSecretsCheck guard(String authSecret, String roundSecret, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new StartupSecretsCheck(authSecret, roundSecret, env);
    }

    private static StartupSecretsCheck onDefaults(String... profiles) {
        return guard(StartupSecretsCheck.DEV_AUTH_SECRET, StartupSecretsCheck.DEV_ROUND_SECRET, profiles);
    }

    /**
     * The invariant that actually decays: someone rotates the default in application.yaml and
     * the guard, still comparing against the old literal, silently protects nothing from then
     * on. Reading the file is the only way to notice.
     */
    @Test
    void theLiteralsStillMatchWhatApplicationYamlShips() throws IOException {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(yaml)
                .as("AUTH_JWT_SECRET default")
                .contains("${AUTH_JWT_SECRET:" + StartupSecretsCheck.DEV_AUTH_SECRET + "}");
        assertThat(yaml)
                .as("ROUND_TOKEN_SECRET default")
                .contains("${ROUND_TOKEN_SECRET:" + StartupSecretsCheck.DEV_ROUND_SECRET + "}");
    }

    @Test
    void aProductionBootOnThePublishedSecretsIsRefused() {
        assertThatThrownBy(() -> onDefaults("prod").refuseDevelopmentSecretsOutsideDevelopment())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET")
                .hasMessageContaining("ROUND_TOKEN_SECRET")
                // A message that does not say what to do next is just a locked door.
                .hasMessageContaining("random values of at least 32 characters");
    }

    @Test
    void aDeveloperMachineIsLeftAlone() {
        assertThatCode(() -> onDefaults("dev").refuseDevelopmentSecretsOutsideDevelopment()).doesNotThrowAnyException();
        assertThatCode(() -> onDefaults("test").refuseDevelopmentSecretsOutsideDevelopment()).doesNotThrowAnyException();
        // A list mixing the two still counts as development rather than as a deployment.
        assertThatCode(() -> onDefaults("prod", "dev").refuseDevelopmentSecretsOutsideDevelopment()).doesNotThrowAnyException();
    }

    /**
     * The case an earlier version of this test could not see, because it only ever set *active*
     * profiles. A checkout activates nothing — `spring.profiles.default: dev` supplies the
     * profile — so this is what every ordinary run and the whole test suite actually look like.
     * Reading getActiveProfiles() there returns an empty array, which the guard read as an
     * unnamed deployment and used to refuse. It turned CI red while passing on a machine whose
     * .env made the secrets real, so the check never got that far.
     */
    @Test
    void theDefaultProfileCountsAsDevelopmentEvenWithNothingActive() {
        MockEnvironment env = new MockEnvironment();
        env.setDefaultProfiles("dev");

        assertThatCode(() -> new StartupSecretsCheck(
                StartupSecretsCheck.DEV_AUTH_SECRET, StartupSecretsCheck.DEV_ROUND_SECRET, env)
                .refuseDevelopmentSecretsOutsideDevelopment())
                .doesNotThrowAnyException();
    }

    /** With no default either, nobody has said what this is — and that is not permission. */
    @Test
    void anEnvironmentThatNamesNothingAtAllIsTreatedAsADeployment() {
        MockEnvironment env = new MockEnvironment();
        env.setDefaultProfiles("none-of-the-development-ones");

        assertThatThrownBy(() -> new StartupSecretsCheck(
                StartupSecretsCheck.DEV_AUTH_SECRET, StartupSecretsCheck.DEV_ROUND_SECRET, env)
                .refuseDevelopmentSecretsOutsideDevelopment())
                .isInstanceOf(IllegalStateException.class);
    }

    /** And the file that supplies that default has to keep supplying it. */
    @Test
    void applicationYamlStillDefaultsTheProfileToDev() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("default: dev");
        }
    }

    @Test
    void configuredSecretsBootInProduction() {
        assertThatCode(() -> guard(REAL, REAL + "-round", "prod").refuseDevelopmentSecretsOutsideDevelopment())
                .doesNotThrowAnyException();
    }

    /** Half-configured is the likelier mistake, and the message has to name the half that is left. */
    @Test
    void onlyTheSecretStillOnItsDefaultIsNamed() {
        assertThat(guard(REAL, StartupSecretsCheck.DEV_ROUND_SECRET, "prod").problems())
                .containsExactly("ROUND_TOKEN_SECRET is still the development default.");

        assertThat(guard(StartupSecretsCheck.DEV_AUTH_SECRET, REAL, "prod").problems())
                .containsExactly("AUTH_JWT_SECRET is still the development default.");
    }
}
