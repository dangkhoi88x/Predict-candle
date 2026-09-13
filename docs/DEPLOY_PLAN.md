# Deploy bản demo — kế hoạch

> Nháp 2026-09-13. Mục tiêu: **một đường link chạy thật** để đưa người khác chơi thử,
> không phải hạ tầng production. Chi phí 0 đ, đổi lại là cold start và giới hạn gói free.

## 1. Chọn gì, và vì sao không phải Vercel

| Phần | Dịch vụ | Gói |
|---|---|---|
| App (Spring Boot + frontend) | **Render**, Web Service chạy `Dockerfile` | Free |
| Postgres | **Neon** | Free |
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

## 2. Ràng buộc quan trọng nhất: region Singapore

**Binance trả HTTP 451 cho IP ở Mỹ**, mà region mặc định của cả Render (Oregon) lẫn Neon đều
ở Mỹ. Chọn sai thì app vẫn khởi động, vẫn qua health check, nhưng không lưu được nến nào: tab
chơi trống và log đầy lỗi 451. CI trên GitHub từng dính đúng lỗi này (xem `CandleFixture` trong
CLAUDE.md).

→ Render: `region: singapore` (đã ghi sẵn trong `render.yaml`).
→ Neon: **AWS Asia Pacific (Singapore)**. DB đặt cùng region với app để mỗi truy vấn không
phải đi vòng qua Thái Bình Dương.

## 3. Neon — hướng dẫn từng bước

### 3.1 Tạo project

1. Vào <https://neon.tech> → **Sign up** (đăng nhập bằng GitHub là nhanh nhất).
2. **Create project**:
   - *Project name*: `candles`
   - *Postgres version*: **16**, cho khớp `docker-compose.yml` và CI. Dev chạy 16, test chạy 16
     thì demo cũng nên chạy 16.
   - *Cloud provider / Region*: **AWS · Asia Pacific (Singapore)**
3. Neon tự tạo sẵn database `neondb`, role `neondb_owner` và branch `main`. Dùng luôn `neondb`,
   không cần tạo database riêng: Flyway tự dựng toàn bộ schema từ V1 ở lần chạy đầu.

### 3.2 Lấy connection string và đổi sang JDBC

Ở trang project bấm **Connect**:

- *Branch*: `main`, *Database*: `neondb`, *Role*: `neondb_owner`
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

### 3.3 Nạp dữ liệu từ máy mình trước (khuyên làm)

Lần khởi động đầu tiên phải backfill nến từ Binance. Trên instance free của Render (CPU yếu,
512 MB) bước này chậm. Chạy nó từ máy local vào thẳng Neon thì Render lên là có dữ liệu ngay:

```bash
DB_URL='jdbc:postgresql://ep-…ap-southeast-1.aws.neon.tech/neondb?sslmode=require' DB_USERNAME=neondb_owner DB_PASSWORD='…' CANDLES_BACKFILL_START=2025-01-01T00:00:00Z ./mvnw spring-boot:run
```

- Biến môi trường ưu tiên hơn `.env`, nên `.env` local không ghi đè được chúng.
- `CANDLES_BACKFILL_START` phải **giống giá trị trong `render.yaml`**. Sync chỉ lấy tiếp từ nến
  mới nhất, không bao giờ quay ngược về trước, nên backfill local từ 2022 thì Neon sẽ giữ lịch
  sử từ 2022.
- Đợi đủ các dòng `Synced N candles for …` (BTC, ETH, BNB, SOL) rồi `Ctrl+C`.

Kiểm tra trong **SQL Editor** của Neon:

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
`main`, trỏ local vào connection string của branch đó để chạy thử, xong thì xoá. Dữ liệu demo
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
| `autoDeployTrigger: checksPass` | Chỉ deploy khi CI trên `main` xanh |

Có thể thêm `CLOUDINARY_CLOUD_NAME` / `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET` ở tab
**Environment** nếu muốn upload ảnh blog trên bản demo. Không có thì chỉ riêng tính năng đó
không chạy.

### 4.2 Reown (đăng nhập bằng ví) — dễ quên nhất

`web/src/wallet-auth.js` dùng project Reown `2c3bd10f…`. Vào dashboard Reown (WalletConnect
Cloud) → project đó → **allowlist domain** → thêm `https://candles.onrender.com` (hoặc domain
Render thực tế cấp). Thiếu bước này thì nút kết nối ví chạy trên localhost nhưng lỗi trên bản
live. Không cần build lại bundle, vì `metadata.url` đọc từ `window.location.origin`.

### 4.3 Giữ app thức

Service free của Render **ngủ sau 15 phút không có request**. Hậu quả:

- Người mở trang lúc app đang ngủ phải chờ JVM khởi động (khoảng 30–60 giây).
- Job sync mỗi giờ không chạy trong lúc ngủ. Dữ liệu không mất: lần thức dậy sau, sync tự lấy
  bù từ nến mới nhất đã lưu.

Cách xử lý: tạo cron trên <https://cron-job.org> gọi `GET https://candles.onrender.com/` mỗi
**10 phút**. Gọi `/` vì đó là file tĩnh, không chạm DB hay Binance, nên không tốn compute hours
của Neon. Giờ free của Render mỗi tháng đủ cho một service chạy 24/7.

## 5. Kiểm tra sau deploy

- [ ] Log Render có `Started CandlesApplication`, không có `Refusing to run with development
      secrets`
- [ ] Log **không** có HTTP 451. Nếu có thì region sai (§2)
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
| Muốn domain riêng | Render → **Settings → Custom Domains**, rồi thêm domain đó vào allowlist Reown (§4.2) |

## 7. Chưa làm, và cố ý chưa làm

- **Nhiều instance.** Rate limiter, cache giá live và repeat cache của practice đều nằm trong
  bộ nhớ từng instance, còn job sync sẽ chạy trùng trên mỗi instance. Scale ngang cần giải
  quyết mấy thứ đó trước. Với demo, một instance là đúng.
- **Backup riêng.** Neon có restore theo thời điểm trong một khoảng ngắn. Dữ liệu demo lấy lại
  được từ Binance, còn tài khoản thì mất cũng không sao.
- **Monitoring / alerting.** Log trên dashboard Render là đủ cho quy mô này.
