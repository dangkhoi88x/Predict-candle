# Operations

Where the demo runs and what keeps it up: Render and Neon, the AOT cache, the health check, the price source and the nightly backup. `docs/DEPLOY_PLAN.md` is the step-by-step walkthrough; this is the why.

- **The demo runs on Render (`render.yaml`) against Neon Postgres, both in Singapore** —
  `docs/DEPLOY_PLAN.md` is the walkthrough. The region is not a latency choice: Binance
  answers US addresses with 451, and both providers default to the US, so a wrong region boots
  cleanly and stores no candles. Not Vercel: there is no Java runtime there, and the hourly
  sync needs a process that stays alive.

- **`index.html`'s `og:url` and `og:image` are absolute and name the Render address**, because
  Facebook and Zalo ignore relative ones. Moving the site means changing both. The image is
  rendered from `web/og/og-image.html` with headless Chrome (the command is in that file), so it
  is re-rendered rather than redrawn when the brand or copy changes.

- **The image carries an AOT cache (`extracted/app.aot`), recorded by a training run in the
  `Dockerfile`**, because a free instance sleeps and whoever wakes it waits for the JVM on a tenth
  of a CPU. It cut the CPU a start burns before its first answer from 8.3s to 5.2s. Two things there
  are load-bearing. `-XX:-AOTClassLinking` stays: with linking on, a start whose heap differs from
  the training run's **refuses to boot** instead of falling back, which is what happened with the
  heap `render.yaml` gives. And the ENTRYPOINT's classpath must stay `extracted/app.jar`, the path
  the cache was recorded against; a different one just ignores the cache and starts slowly. A
  training run that fails fails the image build, which CI does on every push.

- **The API calls a page load makes are slow together, not one by one — do not go looking for a
  slow endpoint.** A live Lighthouse trace showed `/api/daily/round` taking 5s and
  `/api/live/round` 3.5s, and that read as a slow endpoint. Measured apart, unthrottled, against
  `/healthz` (which does nothing, so its ~306 ms is the network to Singapore): every endpoint the
  page loads costs 0–125 ms of server time, `/api/daily/round` about 90. Fired together, as the
  page does, six of them come back spread over ~280–975 ms, about 100 ms apart — a 0.1 CPU working
  through them one after another. The 5s was mostly the tool: Lighthouse's devtools throttling
  adds ~560 ms of latency to *every* request and shares ~1.5 Mbps across everything in flight, so
  seven API calls stack on top of the queue. Deferring the side cards' requests would only reorder
  the same wait, and the one thing that shortens the queue is more CPU, i.e. a paid instance.
  Two measuring rules came out of it: split server time from network by timing each call alone
  against `/healthz` before believing a waterfall, and never set a simulated-throttling score
  beside a devtools-throttling one — the same page read 77 on the first and 85 on the second.

- **`/healthz` answers the two things that ping this deployment — Render's health check and the
  keep-alive cron — and it touches nothing.** A check that queried the database would read a Neon
  compute waking from idle as a dead instance, and Render restarts those: one second would become
  a cold start. It also replaced `/` in `render.yaml`, which built 77 KB of game page per check.
  The cron ping moved for a different reason worth remembering: cron-job.org fails a job whose
  response is too large, so the ping at `/` was marked failed every run until the job was switched
  off, and the demo then slept through every visit. `docs/DEPLOY_PLAN.md` §4.3 has the settings.

- **The demo reads OKX, not Binance: `candles.price-source`, `binance` by default.** Binance
  bans by IP (HTTP 418, 2 minutes growing to 3 days), Render's outbound addresses are shared, and
  the Singapore range was banned twice on the first evening for traffic this app did not send.
  `OkxProvider` and `BinanceProvider` are the two `PriceDataProvider`s, one bean by
  `@ConditionalOnProperty`; OKX pages newest first, dashes its instruments (`BTC-USDT`) and
  reports errors as HTTP 200 with a non-zero `code`, which is what the class hides. Volume is in
  the base asset on both, so nothing downstream knows which one it is reading.
  `BinanceProvider` also stops calling for the `Retry-After` of any 418/429, because every page
  polls the live round and each request sent into a ban is what lengthens it.

- **The demo database is dumped nightly by `.github/workflows/backup.yml`, encrypted, because
  the repository is public** — any signed-in GitHub user can download an artifact, and the dump
  holds every wallet address and play history. `scripts/backup-db.sh` refuses to produce a file
  unless the tables that cannot be re-fetched (users, guesses, live calls, quiz, demo, Flyway
  history) all have a data section, since `pg_dump` against the wrong or an empty database exits
  0. Both scripts run inside `postgres:16`, which carries `pg_dump` and `gpg`, so Docker is the
  only requirement. `restore-db.sh` targets an empty database — a Neon branch, never the live one.
  Without the two secrets the job skips instead of failing, so a fork is not red every night.
