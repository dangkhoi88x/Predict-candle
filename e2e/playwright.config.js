// @ts-check
const { defineConfig, devices } = require("@playwright/test");

const PORT = 8090;

module.exports = defineConfig({
    testDir: "./tests",
    // One server, one database: tests share both, so they run one at a time rather than
    // racing each other through the rate limiter and the daily's one-attempt rule.
    workers: 1,
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: 0,
    timeout: 60_000,
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
    use: {
        baseURL: `http://localhost:${PORT}`,
        locale: "vi-VN",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
    },
    projects: [
        { name: "desktop", use: { ...devices["Desktop Chrome"] } },
        { name: "phone", use: { ...devices["Pixel 7"] } },
    ],
    webServer: {
        // The real application from the test classpath, with its own database and no exchange —
        // see E2eApplication. Seeding happens after the port opens, so the server is only
        // "up" once a practice round can actually be dealt.
        command: "./mvnw -q spring-boot:test-run -Dspring-boot.test-run.main-class=com.example.candles.E2eApplication",
        cwd: "..",
        url: `http://localhost:${PORT}/api/daily/round`,
        reuseExistingServer: !process.env.CI,
        timeout: 240_000,
        stdout: "ignore",
        stderr: "pipe",
    },
});
