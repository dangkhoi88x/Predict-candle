# Candle Guess

Game đoán hướng nến tiếp theo (Long/Short) trên dữ liệu giá thật (BTC/USDT, SOL/USDT, khung 1H).
Xem 4 cây nến gần nhất, đoán cây nến thứ 5 sẽ tăng hay giảm.

MVP hiện tại: chỉ **Practice mode** (random không giới hạn), điểm số/streak lưu ở trình duyệt (chưa có tài khoản).

## Kiến trúc

```
Binance API (klines) → CandleSyncScheduler (backfill + cron mỗi giờ) → Postgres
                                                                          │
                                    RoundSelectionService (random, chống lặp, lọc round "chết")
                                                                          │
                                    RoundTokenService (ký JWT chứa đáp án, stateless)
                                                                          │
                        REST API (GET /api/practice/round, POST /api/practice/guess)
                                                                          │
                            Frontend tĩnh (lightweight-charts + Long/Short) tại "/"
```

## Yêu cầu

- Java 25+
- Docker (chạy Postgres cục bộ) — hoặc tự trỏ tới một Postgres có sẵn
- Không cần cài Maven, dùng `./mvnw` đi kèm

## Chạy lần đầu

**1. Khởi động Postgres:**

```bash
docker compose up -d
```

Mặc định expose ở `localhost:5544` (đã tránh cổng `5432`/`5433` phổ biến để không đụng Postgres khác đang chạy trên máy — đổi lại trong [docker-compose.yml](docker-compose.yml) và `DB_URL` bên dưới nếu cần).

**2. Chạy app:**

```bash
./mvnw spring-boot:run
```

Lần chạy đầu tiên app sẽ tự:
- Tạo bảng (`ddl-auto: update`)
- Insert 2 asset (BTCUSDT, SOLUSDT)
- Backfill ~40.000 nến/asset từ Binance (2022-01-01 → hiện tại) — mất khoảng 15-30 giây, xem log `Synced N candles for ...` để biết đã xong.

**3. Mở trình duyệt:**

```
http://localhost:8080
```

Xem 4 nến, bấm LONG/SHORT để đoán, "Vòng tiếp theo" để chơi tiếp.

## Cấu hình

Tất cả nằm trong [application.yaml](src/main/resources/application.yaml), override qua biến môi trường:

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5544/candles` | JDBC URL Postgres |
| `DB_USERNAME` / `DB_PASSWORD` | `candles` / `candles` | Thông tin đăng nhập DB |
| `ROUND_TOKEN_SECRET` | secret dev mặc định | Khóa ký JWT cho `roundToken` — **bắt buộc đổi khi deploy thật** |

Các cấu hình khác (asset list, ngày backfill, ngưỡng lọc round "chết", TTL cache chống lặp...) sửa trực tiếp trong `application.yaml` phần `candles.*`.

## API

**`GET /api/practice/round?asset=BTCUSDT`** (hoặc `SOLUSDT`)

Trả về 4 nến gần nhất + `roundToken` (JWT chứa đáp án đã ký, client không đọc được).

**`POST /api/practice/guess`**

```json
{ "roundToken": "...", "direction": "LONG" }
```

Trả về `correct`, `actualDirection`, `actualCandle`. Token hết hạn sau 10 phút.

## Cấu trúc thư mục

```
src/main/java/com/example/candles/
├── domain/       Entity (Asset, Candle) + enum (AssetType, Direction)
├── repository/   Spring Data JPA repository
├── provider/     PriceDataProvider (interface) + BinanceProvider
├── ingestion/     Backfill + đồng bộ nến định kỳ (CandleSyncService/Scheduler)
├── round/         Chọn round ngẫu nhiên + sinh/verify roundToken (JWT)
├── api/           REST controller + DTO + exception handler
└── config/        CandlesProperties, RestClient bean

src/main/resources/
├── application.yaml
└── static/        Frontend tĩnh: index.html, app.js (lightweight-charts), style.css
```

## Việc để dành cho giai đoạn sau

Xem phần backlog trong tài liệu thiết kế gốc: thêm vàng (XAU/USD), Daily Challenge + leaderboard, tài khoản người dùng, ẩn/chuẩn hoá chart chống tra cứu, chọn nhiều khung thời gian.

## Ghi chú kỹ thuật

- Dự án dùng **Spring Boot 4.1.1**, đã chuyển sang **Jackson 3** (`tools.jackson.*`), khác với Jackson 2 (`com.fasterxml.jackson.*`) quen thuộc — lưu ý khi thêm code xử lý JSON thủ công.
- JWT dùng `jjwt` với serializer Gson (`jjwt-gson`) để tránh xung đột với Jackson 3.
