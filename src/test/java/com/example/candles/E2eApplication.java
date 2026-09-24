package com.example.candles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;

import com.example.candles.entity.Asset;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.CandleRepository;

/**
 * The app the browser tests in {@code e2e/} run against: the real application, on its own port
 * and its own database, with a candle history that does not depend on reaching an exchange.
 *
 * <p>Started by {@code ./mvnw spring-boot:test-run}, which is what puts {@link CandleFixture} on the
 * classpath — the same fixture the Java suite seeds from, so a browser test and a flow test agree
 * about what market they are playing.
 *
 * <p>Four things are set here rather than left to configuration, and each is the reason this
 * class exists:
 * <ul>
 *   <li><b>Its own database</b> ({@code candles_e2e}), created if missing. A test that plays a
 *       chart against the development database would leave calls in somebody's history, and one
 *       that ran against an empty one would find no chart to deal.</li>
 *   <li><b>No exchange.</b> Both providers point at a port nothing listens on, so the startup sync
 *       fails at once instead of spending the first minute backfilling, and a run on CI — which
 *       Binance refuses from — behaves exactly like one on a laptop. The live round and the trade
 *       terminal have no price as a result; nothing in {@code e2e/} tests them.</li>
 *   <li><b>Port 8090</b>, so a development server on 8080 can keep running beside it.</li>
 *   <li><b>Rate limits out of the way.</b> Every test is one client on localhost, and a full run
 *       deals well over the forty charts a minute a player is allowed — the daily then reads
 *       "Bạn thao tác hơi nhanh" instead of drawing its start button. The limiter has its own
 *       tests in the Java suite; these are about what a page does.</li>
 * </ul>
 * Any of them can be overridden with a system property or an environment variable, which is how
 * CI points it at the database its service container created.
 */
public final class E2eApplication {

    private static final Logger log = LoggerFactory.getLogger(E2eApplication.class);

    private static final Map<String, String> DEFAULTS = Map.of(
            "server.port", "8090",
            "spring.datasource.url", "jdbc:postgresql://localhost:5544/candles_e2e",
            "candles.binance.base-url", "http://127.0.0.1:9",
            "candles.okx.base-url", "http://127.0.0.1:9",
            "candles.round.rate-limit.rounds-per-minute", "1000",
            "candles.round.rate-limit.guesses-per-minute", "1000");

    private E2eApplication() {
    }

    public static void main(String[] args) throws SQLException {
        DEFAULTS.forEach((key, value) -> {
            String env = key.toUpperCase().replace('.', '_').replace('-', '_');
            if (System.getProperty(key) == null && System.getenv(env) == null) {
                System.setProperty(key, value);
            }
        });
        createDatabaseIfMissing(property("spring.datasource.url"));
        SpringApplication.from(CandlesApplication::main).with(Seed.class).run(args);
    }

    private static String property(String key) {
        String env = System.getenv(key.toUpperCase().replace('.', '_').replace('-', '_'));
        return env != null ? env : System.getProperty(key);
    }

    /** Postgres will not create a database on connect, and Flyway needs one to migrate. */
    private static void createDatabaseIfMissing(String url) throws SQLException {
        int slash = url.lastIndexOf('/');
        int query = url.indexOf('?', slash);
        String name = url.substring(slash + 1, query < 0 ? url.length() : query);
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Unexpected database name: " + name);
        }
        String user = firstNonNull(property("spring.datasource.username"), "candles");
        String password = firstNonNull(property("spring.datasource.password"), "candles");
        try (Connection admin = DriverManager.getConnection(url.substring(0, slash) + "/postgres", user, password);
             PreparedStatement exists = admin.prepareStatement("select 1 from pg_database where datname = ?")) {
            exists.setString(1, name);
            try (ResultSet row = exists.executeQuery()) {
                if (row.next()) return;
            }
            try (Statement create = admin.createStatement()) {
                create.execute("create database " + name);
            }
            log.info("Created database {} for the browser tests", name);
        }
    }

    private static String firstNonNull(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Seeds after the application is ready rather than as a runner of its own: the pairs are
     * created by {@code CandleSyncScheduler}'s runner, and two runners without an order run in
     * whichever order they happen to be found. {@link CandleFixture#seedIfEmpty} leaves a pair that
     * already has history alone, so a second start keeps the market the first one played.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Seed {

        private final AssetRepository assets;
        private final CandleRepository candles;

        Seed(AssetRepository assets, CandleRepository candles) {
            this.assets = assets;
            this.candles = candles;
        }

        @EventListener(ApplicationReadyEvent.class)
        void seed() {
            for (Asset asset : assets.findAllByOrderByPositionAscSymbolAsc()) {
                int written = CandleFixture.seedIfEmpty(candles, asset, "1h");
                if (written > 0) log.info("Seeded {} candles for {}", written, asset.getSymbol());
            }
            log.info("E2E server ready");
        }
    }
}
