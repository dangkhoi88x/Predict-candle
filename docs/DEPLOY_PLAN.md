# Deploy bản demo — kế hoạch

> Viết 2026-09-13, **đang chạy** từ 2026-09-14 tại <https://candles-oj1q.onrender.com>.
> Mục tiêu: một đường link chạy thật để đưa người khác chơi thử, không phải hạ tầng
> production. Chi phí 0 đ, đổi lại là giới hạn của các gói free.

## 1. Chọn gì, và vì sao không phải Vercel

| Phần | Dịch vụ | Gói |
|---|---|---|
| App (Spring Boot + frontend) | **Render**, Web Service chạy `Dockerfile` | Free |
| Postgres | **Neon** | Free |
| Nguồn giá | **OKX**, API công khai | Free |
| Giữ app thức | **cron-job.org** | Free |

**Vercel không chạy được app này.** Nó dành cho frontend tĩnh và hàm serverless ngắn, còn
Candles là một tiến trình Java chạy liên tục:

- Vercel không có runtime Java.
- `CandleSyncScheduler` sync nến lúc khởi động và mỗi giờ (`@Scheduled`). Serverless không có
  tiến trình nào sống để chạy lịch đó.
- `RateLimiter` và cache giá live nằm trong bộ nhớ, sẽ reset sau mỗi lần gọi hàm.
- Frontend do chính Spring phục vụ, cùng origin với API. Refresh cookie để `SameSite=Strict`,
  nên tách frontend sang domain khác sẽ làm hỏng đăng nhập.

Dùng Vercel làm proxy phía trước (rewrite `/(.*)` sang Render) thì chạy được, nhưng chỉ đổi
lấy cái tên `*.vercel.app`. Cái giá là thêm một tầng mạng, cold start dễ vượt timeout proxy,
và IP thật phải đi qua hai tầng proxy mới tới rate limiter. Muốn domain đẹp thì gắn thẳng vào
Render.

**Postgres để ở Neon chứ không dùng của Render**, vì Postgres free của Render bị xoá sau 30
ngày, kéo theo toàn bộ tài khoản và lịch sử.

## 2. Region Singapore, nguồn giá OKX

**Render và Neon cùng ở Singapore** để app và database nằm cạnh nhau: mỗi nến lưu vào là một
lệnh insert riêng (§3.3), nên độ trễ giữa hai bên quyết định lần backfill đầu mất vài phút hay
vài tiếng.

→ Render: `region: singapore` (đã ghi sẵn trong `render.yaml`).
→ Neon: **AWS Asia Pacific (Singapore)**.

**Bản demo lấy giá từ OKX, không phải Binance**; chạy local vẫn dùng Binance. Lý do là đêm
deploy đầu tiên, 2026-09-13:

1. Trên Render Singapore, `api.binance.com` không trả được nến nào. Chuyển sang
   `data-api.binance.vision` cũng vậy.
2. Lỗi thật hoá ra là **HTTP 418**: Binance cấm theo IP, lần đầu 45 phút, sau đó 2 tiếng, và
   mỗi lần tái phạm lại dài hơn, tối đa 3 ngày. IP gọi ra ngoài của Render được nhiều service
   dùng chung, và bot crypto chạy trên Render rất nhiều. App này chỉ gửi vài chục request,
   không thể tự gây ra lệnh cấm, nên cũng không có cách nào tự gỡ.
3. Đổi region chỉ là đổi sang một dải IP chung khác, không có gì đảm bảo. Đổi sàn thì giải quyết
   được: OKX chạy ổn trên Render từ lần deploy 2026-09-14.

`render.yaml` đặt `CANDLES_PRICE_SOURCE=okx`. Hai provider (`BinanceProvider`, `OkxProvider`)
trả nến theo cùng một hợp đồng, nên phần còn lại của app không biết mình đang đọc sàn nào.
Binance vẫn trả **HTTP 451 cho IP ở Mỹ** (lý do CI không gọi sàn thật, xem `CandleFixture`), nên
nếu có lúc quay lại Binance thì đừng để region mặc định Oregon.

**Khi bản live không có giá, hỏi thẳng API thay vì tìm trong log.** Log trên Render chỉ hiện
phần đuôi stack trace, dòng nêu nguyên nhân bị cuộn mất. Mỗi lần gọi sàn thất bại, app trả
**502** kèm lý do:

```bash
curl -s https://candles-oj1q.onrender.com/api/live/round?asset=BTCUSDT
```

Ví dụ `Không lấy được dữ liệu từ sàn (HTTP 418).` hoặc
`… (sàn đang tạm chặn, thử lại sau 2026-09-13T14:40:42Z)`. Endpoint này gọi sàn nhưng không đọc
nến trong database, nên nó tách được lỗi của sàn khỏi lỗi của dữ liệu.

## 3. Neon — hướng dẫn từng bước

### 3.1 Tạo project

1. Vào <https://neon.tech> → **Sign up** (đăng nhập bằng GitHub là nhanh nhất).
2. **Create project**:
   - *Project name*: `candles`
   - *Postgres version*: **16**, cho khớp `docker-compose.yml` và CI. Dev chạy 16, test chạy 16
     thì demo cũng nên chạy 16.
   - *Cloud provider / Region*: **AWS · Asia Pacific (Singapore)**
3. Neon tự tạo sẵn database `neondb`, role `neondb_owner` và branch `production`. Dùng luôn `neondb`,
   không cần tạo database riêng: Flyway tự dựng toàn bộ schema từ V1 ở lần chạy đầu.

### 3.2 Lấy connection string và đổi sang JDBC

Ở trang project bấm **Connect**:

- *Branch*: `production`, *Database*: `neondb`, *Role*: `neondb_owner`
- **Tắt "Connection pooling"** để lấy endpoint direct, tức host **không** có `-pooler`. Demo
  chỉ có một instance với pool Hikari mặc định 10 kết nối, nên không cần PgBouncer. Bỏ nó đi
  cũng bỏ luôn một lớp có thể gây rắc rối với lock của Flyway và prepared statement.

Neon đưa ra chuỗi dạng:

```
postgresql://neondb_owner:AbC123xyz@ep-cool-name-a1b2c3d4.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require
```

App đọc ba biến riêng (`application.yaml`). **Driver JDBC không đọc `user:password@` trong
URL**, nên phải tách ra:

```
DB_URL=jdbc:postgresql://ep-cool-name-a1b2c3d4.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
DB_USERNAME=neondb_owner
DB_PASSWORD=AbC123xyz
```

Nhớ thêm tiền tố `jdbc:` và giữ `sslmode=require`. `channel_binding` bỏ đi được.

### 3.3 Để Render nạp nến, đừng nạp từ máy local

Lần khởi động đầu tiên phải backfill nến từ sàn, khoảng 58 nghìn dòng nếu tính từ 2025.
**Việc này nên để Render làm**, dù instance free của nó yếu hơn máy bạn.

Lý do là độ trễ mạng, không phải CPU. `Candle` sinh id bằng `IDENTITY`, nên Hibernate không gộp
được các lệnh insert: mỗi nến là một lượt đi về tới database. Từ Việt Nam tới Neon Singapore mỗi
lượt mất khoảng 70 ms, tức hơn một tiếng cho 58 nghìn dòng. Render ở cùng region với Neon nên
mỗi lượt chỉ vài ms, và cùng khối lượng đó xong trong vài phút. Thử thật ngày 2026-09-13: chạy
local vào Neon, riêng 17 migration đã mất 14 giây.

Chạy app local trỏ vào Neon vẫn có ích để **kiểm tra kết nối và tạo schema** trước khi deploy.
Thấy `Successfully applied … migrations` và `Started CandlesApplication` là đủ, bấm `Ctrl+C`:

```bash
DB_URL='jdbc:postgresql://ep-…ap-southeast-1.aws.neon.tech/neondb?sslmode=require' DB_USERNAME=neondb_owner DB_PASSWORD='…' CANDLES_BACKFILL_START=2025-01-01T00:00:00Z ./mvnw spring-boot:run
```

- Biến môi trường ưu tiên hơn `.env`, nên `.env` local không ghi đè được chúng.
- Giữ `CANDLES_BACKFILL_START` **giống giá trị trong `render.yaml`**. Sync chỉ lấy tiếp từ nến
  mới nhất, không bao giờ quay ngược về trước, nên nếu một cặp kịp lưu xong từ 2022 thì Neon sẽ
  giữ lịch sử từ 2022.
- Dừng giữa chừng không làm hỏng gì. Mỗi cặp tiền được lưu trong một transaction
  (`saveAll`), nên cặp đang nạp dở sẽ rollback, còn cặp đã xong thì Render bỏ qua.

Sau khi Render deploy xong và log có đủ 4 dòng `Synced N candles for …`, kiểm tra trong
**SQL Editor** của Neon:

```sql
select version, description, success from flyway_schema_history order by installed_rank desc limit 3;
select a.symbol, count(*), min(c.open_time), max(c.open_time)
from candles c join assets a on a.id = c.asset_id group by a.symbol;
```

Kết quả đúng là 4 cặp tiền, mỗi cặp khoảng 14–15 nghìn nến từ 2025.

### 3.4 Giới hạn của gói free cần biết

- **Storage 0.5 GB.** Backfill từ 2025 chiếm một phần nhỏ trong số đó. Từ 2022 thì gấp khoảng
  ba lần, vẫn vừa nhưng nên xem tab **Usage** sau lần nạp đầu.
- **Scale to zero:** compute tự tắt sau khoảng 5 phút rảnh. Truy vấn đầu tiên sau đó chờ thêm
  chừng một giây để Neon bật lại. **Nhưng mặc định Hikari giữ 10 kết nối mở mãi**, nên compute
  không bao giờ được coi là rảnh: nó chạy 24/7 và đốt hết compute hours của tháng dù không ai
  chơi. Vì vậy `render.yaml` đặt `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0` và
  `SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT=60000` để pool tự đóng kết nối sau một phút rảnh. Cái
  giá là một lần bắt tay TLS cho truy vấn đầu tiên sau khoảng lặng.
- **Compute hours mỗi tháng có hạn.** Sync mỗi giờ chỉ đánh thức DB vài phút nên tốn ít. Nếu
  demo có nhiều người chơi liên tục thì theo dõi tab **Usage**.
- Con số cụ thể của gói free Neon thay đổi theo thời gian, nên kiểm tra trên trang pricing khi
  làm.

### 3.5 Tiện ích nên dùng: branch

Neon cho **branch database** như branch git: một bản copy tức thì, không tốn thêm dung lượng
cho phần chưa đổi. Trước khi thử một migration mới lên dữ liệu demo, tạo branch `test-vXX` từ
`production`, trỏ local vào connection string của branch đó để chạy thử, xong thì xoá. Dữ liệu demo
không bị ảnh hưởng.

## 4. Render

### 4.1 Tạo service từ Blueprint

1. Merge `render.yaml` vào `main`.
2. <https://dashboard.render.com> → **New → Blueprint** → chọn repo `Candles`.
3. Render đọc `render.yaml` và hỏi các biến `sync: false`:
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: ba giá trị từ §3.2
   - `ADMIN_WALLETS`: địa chỉ ví admin, cách nhau bằng dấu phẩy
4. **Apply**. Lần build đầu mất vài phút vì Maven phải tải toàn bộ dependency trong
   `Dockerfile`.

Những gì `render.yaml` đã lo sẵn, giải thích chi tiết nằm trong comment của file:

| Biến | Tác dụng |
|---|---|
| `AUTH_JWT_SECRET`, `ROUND_TOKEN_SECRET` | Render tự sinh. Thiếu thì `StartupSecretsCheck` chặn khởi động |
| `AUTH_COOKIE_SECURE=true` | Refresh cookie chỉ gửi qua https |
| `PORT`, `SERVER_PORT` = 8080 | Render và Spring thống nhất một cổng |
| `SERVER_FORWARD_HEADERS_STRATEGY=native` | Rate limiter thấy IP thật thay vì IP của proxy |
| `JAVA_TOOL_OPTIONS` | JVM tự co heap theo 512 MB |
| `SPRING_DATASOURCE_HIKARI_*` | Pool đóng kết nối khi rảnh để Neon được scale to zero (§3.4) |
| `CANDLES_BACKFILL_START` | Backfill từ 2025 |
| `CANDLES_PRICE_SOURCE=okx` | Lấy nến và giá live từ OKX thay vì Binance (§2) |
| `autoDeployTrigger: checksPass` | Chỉ deploy khi CI trên `main` xanh |

Có thể thêm `CLOUDINARY_CLOUD_NAME` / `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET` ở tab
**Environment** nếu muốn upload ảnh blog trên bản demo. Không có thì chỉ riêng tính năng đó
không chạy.

### 4.2 Reown (đăng nhập bằng ví) — dễ quên nhất

`web/src/wallet-auth.js` dùng project Reown `2c3bd10f…`. Vào dashboard Reown (WalletConnect
Cloud) → project đó → **allowlist domain** → thêm `https://candles-oj1q.onrender.com`, đúng
domain Render cấp, không có `/` ở cuối. Thiếu bước này thì nút kết nối ví chạy trên localhost nhưng lỗi trên bản
live. Không cần build lại bundle, vì `metadata.url` đọc từ `window.location.origin`.

### 4.3 Giữ app thức

Service free của Render **ngủ sau 15 phút không có request**. Hậu quả:

- Người mở trang lúc app đang ngủ phải chờ JVM khởi động (khoảng 30–60 giây).
- Job sync mỗi giờ không chạy trong lúc ngủ. Dữ liệu không mất: lần thức dậy sau, sync tự lấy
  bù từ nến mới nhất đã lưu.

Cách xử lý: tạo cron trên <https://cron-job.org> gọi `GET https://candles-oj1q.onrender.com/healthz`
mỗi **10 phút**. Endpoint này trả đúng hai ký tự, không chạm database hay sàn, nên không tốn
compute hours của Neon và không gửi thêm request tới OKX.

- **Đừng trỏ vào `/`.** Job đầu tiên gọi `/` và cron-job.org đánh dấu *failed (output too large)*
  mỗi lần chạy, vì trang chủ trả 77 KB. Request vẫn đánh thức app, nhưng sau một loạt lỗi liên
  tiếp cron-job.org **tự tắt job** — và demo ngủ suốt từ đó, cho tới khi ai đó mở trang và phải
  chờ hai phút. Đây là lý do `/healthz` tồn tại.
- **Timeout:** đặt mức cao nhất cho phép. Lượt gọi trúng lúc app đang khởi động mất 1–2 phút;
  với timeout 30 giây mặc định thì nó vẫn đánh thức được app, nhưng lại bị ghi *failed*, và đủ
  nhiều lần *failed* là job bị tắt.
- **Notifications:** bật báo lỗi sau vài lần thất bại liên tiếp — cái này chỉ hữu ích khi job
  không còn *failed* vì những lý do vô hại như trên.
- **Kiểm tra định kỳ:** mở lịch sử chạy của job. Job bị tắt không báo gì trên Render, và triệu
  chứng duy nhất là trang chờ "waking up" của Render khi có người vào xem.
- **Giờ free:** Render cho 750 giờ mỗi tháng cho cả workspace, một service chạy 24/7 dùng khoảng
  720–744 giờ. Thêm một web service free thứ hai cũng giữ thức thì sẽ hết giờ trước cuối
  tháng.
- **Neon vẫn ngủ**, và như vậy là cố ý: `/healthz` không chạm database, nên compute của Neon tự
  tắt khi rảnh. Người dùng đầu tiên sau một lúc vắng chờ thêm khoảng một giây cho nó dậy, đổi lại
  không đốt compute hours của gói free.

### 4.4 Telegram Mini App (tuỳ chọn)

Không có ba biến dưới đây thì app chạy như web thường, `POST /api/auth/telegram` trả 404.

1. Mở Telegram, chat với **@BotFather** → `/newbot` → đặt tên và username (phải kết thúc bằng
   `bot`, ví dụ `candle_guess_bot`). BotFather trả về **token**. Token này là toàn bộ bí mật của
   đăng nhập Telegram: ai có nó thì đăng nhập được thành bất kỳ ai, nên đừng dán vào chat hay commit.
2. Vẫn ở @BotFather → `/newapp` → chọn bot vừa tạo → nhập tên, mô tả, ảnh 640×360 → **Web App URL**
   là `https://candles-oj1q.onrender.com` → **short name**, ví dụ `play`. Link mở app sẽ là
   `https://t.me/candle_guess_bot/play`.
3. (Nên làm) `/setmenubutton` → chọn bot → URL như trên, để nút menu trong chat với bot mở game.
4. Render → service → **Environment**, thêm:
   - `TELEGRAM_BOT_TOKEN` = token ở bước 1
   - `TELEGRAM_BOT_USERNAME` = `candle_guess_bot` (không có `@`)
   - `TELEGRAM_APP_NAME` = `play`

   Lưu lại, Render deploy lại.
5. Kiểm tra: `curl https://candles-oj1q.onrender.com/api/site-config` phải có
   `"telegram":{"login":true,"appLink":"https://t.me/candle_guess_bot/play"}`. Mở link đó trong
   Telegram: tên `@username` hiện góc trên, không cần ví.

Hai biến sau không bắt buộc: thiếu thì đăng nhập vẫn chạy, chỉ là link thách đấu gửi từ trong
Telegram sẽ là link web thay vì link mở lại Mini App.

**Nhận cảnh báo lỗi qua bot (nên bật):** thêm biến `TELEGRAM_ALERT_CHAT_ID` trên Render, giá trị là
chat id của bạn với bot. Lấy id bằng cách nhắn cho bot một câu bất kỳ rồi vào trang admin → Thử
thách → thẻ Telegram → **Tìm chat id của nhóm** (bảng đó liệt kê cả chat riêng). Khi có một loại lỗi
mới xuất hiện, bot nhắn một tin kèm nút mở trang Vận hành; tối đa 15 phút một tin, và số lỗi bị dồn
lại được ghi trong tin kế tiếp. Rỗng thì không gửi gì.

### 4.5 Bot nhắc Thử thách trong nhóm Telegram (tuỳ chọn)

Cần làm xong §4.4 trước. Chỉ gửi vào nhóm nào đã đồng ý: bot không tự gửi chỉ vì được thêm vào nhóm.

1. Trong Telegram, mở nhóm → **Thêm thành viên** → tìm username bot → thêm. Bot không cần quyền
   admin trong nhóm để gửi tin.
2. Trong nhóm gõ `/start@<username_bot>` (ví dụ `/start@Candle_Guess_bot`). Bước này để bot chắc
   chắn "nghe" thấy nhóm; bot vẫn im lặng, như vậy là bình thường.
3. Vào trang admin → **Thử thách** → kéo xuống thẻ **Nhắc trong nhóm Telegram** → bấm
   **Tìm chat id của nhóm**. Chép số ở cột *Chat id*, số âm, siêu nhóm bắt đầu bằng `-100`.
   Telegram chỉ giữ tin cho bot 24 giờ, quá hạn thì làm lại bước 2.
4. Render → **Environment** → thêm `TELEGRAM_DAILY_CHAT_IDS` = chat id đó. Nhiều nhóm thì ngăn
   cách bằng dấu phẩy. Lưu và đợi deploy lại.
5. Quay lại thẻ trên trang admin: dòng trạng thái phải là *đang bật · Thử thách #…*. Bấm
   **Gửi tin sáng ngay** để thử. Tin gửi tay chính là tin của hôm đó, nên lịch 8:00 sẽ bỏ qua nhóm
   đã nhận.

Lịch mặc định là **8:00** (thử thách mới) và **21:00** (top 3 tạm tính), theo giờ Việt Nam. Muốn
đổi giờ thì đặt `TELEGRAM_MORNING_CRON` / `TELEGRAM_EVENING_CRON`, dạng cron 6 trường của Spring,
ví dụ `0 30 7 * * *` là 7:30. Đặt `-` để tắt tin đó. Lịch chỉ chạy khi app đang thức, nên cron
giữ app thức ở §4.3 phải còn chạy.

Muốn dừng nhắc một nhóm thì xoá chat id khỏi biến. Nếu bot bị kick khỏi nhóm, lỗi sẽ hiện ở
**Vận hành → Lỗi gần đây**.

## 5. Kiểm tra sau deploy

- [ ] Log Render có `Started CandlesApplication`, không có `Refusing to run with development
      secrets`
- [ ] `curl …/api/live/round?asset=BTCUSDT` trả 200 có `livePrice`. Nếu ra 502 thì đọc lý do
      trong `message` (§2)
- [ ] `curl …/api/practice/round?asset=<cặp>` trả 200 cho cả 4 cặp. `Not enough candle history`
      nghĩa là backfill chưa xong hoặc đã thất bại
- [ ] Trang chủ mở được và chơi hết một round practice
- [ ] Kết nối ví → đăng nhập → **reload** trang vẫn còn đăng nhập (refresh cookie `Secure`
      chạy được)
- [ ] `/admin.html` bằng ví trong `ADMIN_WALLETS` thấy các pane. Ví khác thì bị chặn
- [ ] Tab live round có giá nhảy, heatmap có dữ liệu
- [ ] Sau khoảng một giờ, log có thêm một lượt `Synced …` (job hourly chạy lúc phút :05)

## 6. Khi gói free không đủ

| Triệu chứng | Làm gì |
|---|---|
| Render báo out of memory, instance restart liên tục | Lên gói trả phí có nhiều RAM hơn trên Render, hoặc chuyển sang Railway Hobby (khoảng 5 USD/tháng), region `asia-southeast1` |
| Cold start làm người thử bỏ đi | Gói trả phí của Render không ngủ, bỏ luôn cron-job.org |
| Neon báo gần hết storage | Xoá bớt nến cũ, hoặc để `CANDLES_BACKFILL_START` muộn hơn trước khi nạp |
| API trả 502 `HTTP 418` / `HTTP 429` hoặc `sàn đang tạm chặn` | Sàn đang cấm IP của Render. App tự ngừng gọi tới mốc thử lại, không cần làm gì. Nếu lặp lại liên tục thì đổi `CANDLES_PRICE_SOURCE` (§2) |
| Muốn domain riêng | Render → **Settings → Custom Domains**, rồi thêm domain đó vào allowlist Reown (§4.2) |

## 7. Chưa làm, và cố ý chưa làm

- **Nhiều instance.** Rate limiter, cache giá live và repeat cache của practice đều nằm trong
  bộ nhớ từng instance, còn job sync sẽ chạy trùng trên mỗi instance. Scale ngang cần giải
  quyết mấy thứ đó trước. Với demo, một instance là đúng.
- **Backup riêng.** Neon có restore theo thời điểm trong một khoảng ngắn. Dữ liệu demo lấy lại
  được từ sàn, còn tài khoản thì mất cũng không sao.
- **Monitoring / alerting.** cron-job.org báo khi trang chủ không lên, và lỗi gọi sàn tự nói lý do
  qua API (§2). Với quy mô này như vậy là đủ.
