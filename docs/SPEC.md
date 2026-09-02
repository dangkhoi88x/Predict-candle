# Candles — Product Spec & Feature Backlog

> Bản nháp v0.1 — 2026-08-31. Tài liệu này gom lại: (1) hiện trạng, (2) danh sách feature đề xuất
> theo phase, (3) khảo sát đối thủ, (4) open source có thể tận dụng.

---

## 1. Hiện trạng (đã có trong repo)

| Mảng | Trạng thái |
|---|---|
| Practice mode | 20 nến hiện, 5 lượt đoán/chart, mỗi lượt lộ thêm 1 nến, `roundToken` JWT stateless |
| Dữ liệu | Binance REST klines, 4 asset (BTC/ETH/BNB/SOL), khung **1h**, backfill từ 2022-01-01 |
| Lọc round | Chống lặp (Caffeine TTL 5'), loại chart "chết" (`min-range-pct`, `min-answer-body-pct`) |
| Auth | Wallet-signature (EIP-191), access token in-memory + refresh HttpOnly cookie, không role |
| Thống kê | `GuessResult` lưu theo user, streak/accuracy/tally theo asset, carry-over điểm từ localStorage |
| Frontend | 1 `index.html`, 6 tab (Đoán Nến, Heatmap, Mẫu Nến, Mẫu Hình KT, Tâm Lý, Blog) + Hồ Sơ |
| Thư viện mẫu | `PatternLibrary` (nến) + `TechnicalPatternLibrary` (mẫu hình, `SwingPivotDetector`), có "tìm ví dụ thật" |
| Heatmap | Crypto (CoinGecko, gọi thẳng từ browser) + S&P 500 (`YahooFinanceClient`), treemap tự viết |
| Media | Cloudinary, giới hạn theo `MEDIA_ADMIN_WALLETS` |

**Đang làm dở (working tree):** `Timeframes` tách khỏi `BinanceProvider` (dọn đường cho multi-timeframe),
revoke session bằng `tokenVersion` khi logout, ngưỡng `min-answer-body-pct`.

**Backlog cũ trong README:** vàng (XAU/USD), Daily Challenge + leaderboard, ẩn/chuẩn hoá chart chống tra cứu,
chọn nhiều khung thời gian.

---

## 2. Định vị sản phẩm

> "GeoGuessr của biểu đồ giá" — luyện đọc price action bằng dữ liệu thật, đo bằng số, chơi được trong 60 giây.

Ba trục giá trị, theo thứ tự ưu tiên:

1. **Đo được** — không phải cảm giác "tôi đọc chart giỏi", mà là win-rate có mẫu số, tách theo asset/khung/hướng.
2. **Công bằng** — server giữ đáp án, chart ẩn danh, không tra Google ra được.
3. **Có lý do quay lại** — daily challenge, streak, bảng xếp hạng, thách đấu bạn bè.

Khác biệt so với đối thủ (mục 4): phần **giáo dục** (thư viện mẫu nến + mẫu hình + tâm lý giao dịch)
đã có sẵn và có thể nối thẳng vào vòng chơi — sau mỗi lượt đoán, chỉ ra mẫu nào vừa xuất hiện.
Không đối thủ nào trong danh sách làm được vòng khép kín "chơi → sai → học đúng chỗ vừa sai".

---

## 3. Feature list

Ký hiệu: **P0** = cần cho bản public đầu tiên · **P1** = giữ chân người chơi · **P2** = mở rộng/kiếm tiền.
Cột "Effort": S ≤ 1 ngày, M ≤ 3 ngày, L ≥ 1 tuần.

### 3.1 Core game

| # | Feature | Mô tả | Effort | Chạm vào |
|---|---|---|---|---|
| G1 ✅ | **Lộ danh tính + toàn cảnh setup** | Kết thúc chart thì hiện ngày giờ thật, link TradingView, và một chart riêng bên dưới vẽ lại vòng chơi kèm 12 nến trước và 12 nến sau — phần người chơi không được xem. Không bắt ai đi tìm ở chỗ khác. | S | `GuessResponse`, `RoundSelectionService`, `app.js` |
| G2 | **Đa khung thời gian** | 15m / 1h / 4h / 1d. `Timeframes` đã tách sẵn; cần ingest thêm khung + pill chọn khung. | M | `CandleSyncService`, `RoundSelectionService`, `application.yaml` |
| G3 | **Đa loại tài sản** | Thêm vàng (XAU/USD), forex major, vài mã cổ phiếu Mỹ. `AssetType` đã có enum, cần provider thứ hai. | L | `provider/`, `AssetType` |
| G4 | **Mức tự tin (confidence)** | Ngoài LONG/SHORT, chọn 1×/2×/3×. Điểm nhân theo mức, sai thì trừ tương ứng. Đo được *calibration*, không chỉ hướng. | M | `GuessRequest`, `PlayerScore`, `GuessResult` |
| G5 | **Chế độ Trade Sim** | Vào lệnh có SL/TP, chart chạy tiếp từng nến, tính PnL theo R. Đây là bước từ "đoán hướng" lên "quản trị lệnh". | L | Module mới `sim/` |
| G6 ✅ | **Đồng hồ đếm ngược** | 20s mỗi lượt, thanh chạy + số giây, đỏ khi còn ≤5s. Hết giờ thì tự gửi lượt bỏ trống, banner ghi "⏱ Hết giờ". Đồng hồ trên màn hình đếm từ lúc nhận token, đúng bằng đồng hồ server đang cưỡng chế. | S | `app.js`, `RoundTimingPolicy` |
| G7 ✅ | **Giải thích sau khi đoán** | Kết thúc vòng, `RoundPatternScanner` quét mẫu nến trên đúng cửa sổ vừa chơi; mỗi mẫu là một chip có tên + giờ, rê chuột thì sáng đúng cụm nến trên chart bối cảnh, bấm thì nhảy sang thẻ đầy đủ ở tab Mẫu Nến. **Chỉ mẫu nến**, không quét mẫu hình kỹ thuật — chúng khớp theo swing pivot và cần nhiều nến hơn một vòng chơi (riêng lookahead breakout đã là 80 nến). | M | `pattern/`, `GuessResponse`, `app.js`, `patterns.js` |

> **Đã chốt: không làm ẩn danh chart.** Bản spec đầu đề xuất che tên asset + chuẩn hoá giá về
> index 100 để chống tra cứu. Bỏ, vì threat model không xứng: mốc thời gian vốn đã không được
> gửi xuống client (`CandleDto` chỉ có OHLC), nên muốn tra ngược phải giải mã `roundToken` rồi
> tự suy offset từ `backfill.start` — công của người cố tình phá, không phải của người chơi.
> **Điều kiện xem lại:** khi có giải đấu có thưởng (P2). Lúc có tiền thì sẽ có người rảnh.
> Khi đó việc cần làm là mã hoá payload `roundToken` (JWE) — chứ không phải giấu giá.

### 3.2 Vòng lặp giữ chân

| # | Feature | Mô tả | Effort | Chạm vào |
|---|---|---|---|---|
| R1 | **Daily Challenge** | Mọi người chơi **cùng một** bộ 5 chart mỗi ngày (seed = ngày UTC). Chơi 1 lần/ngày. Đây là feature viral quan trọng nhất. | M | `round/`, entity `DailyChallenge` |
| R2 | **Chia sẻ kết quả kiểu Wordle** | `📈 Candles #142 — 4/5 🟩🟩🟥🟩🟩` copy vào clipboard, không lộ đáp án. | S | `app.js` |
| R3 | **Leaderboard** | Ngày / tuần / mọi lúc. Tách bảng cho Daily Challenge (công bằng vì cùng chart) và bảng practice (theo accuracy có mẫu số tối thiểu). | M | `stats/`, endpoint `/api/leaderboard` |
| R4 | **Rating kiểu Elo** | Độ khó của round tính từ tỉ lệ đoán đúng cộng đồng; đoán đúng round khó ăn nhiều điểm hơn. Cần đủ traffic mới bật. | L | `stats/`, cột `difficulty` trên round |
| R5 | **Streak & thành tựu** | Chuỗi ngày chơi liên tiếp, badge ("30 lượt liên tiếp không lệch bias", "thắng chart 15m"). | M | `domain/`, `profile.js` |
| R6 | **Thách đấu 1v1 (async)** | Sinh link chứa seed, người nhận chơi đúng bộ chart đó, so kết quả. Không cần realtime, không cần websocket. | M | `round/`, view mới |
| R7 | **Phân tích thiên kiến** | "Bạn chọn LONG 68% số lượt nhưng thị trường chỉ tăng 51%" — bias theo hướng, theo asset, theo khung, theo giờ chơi. Đây là thứ đối thủ không có và nó *đáng tiền*. | M | `StatsService`, `profile.js` |

### 3.3 Nền tảng & vận hành

| # | Feature | Mô tả | Effort |
|---|---|---|---|
| N1 | **Backfill bằng bulk zip** | Chuyển backfill sang `data.binance.vision` (zip theo tháng) thay vì phân trang REST. Bắt buộc nếu thêm khung 15m × nhiều asset (~35k nến/năm/asset ở 15m). | M |
| N2 ✅ | **Chống gian lận** | Rate limit 40 round + 120 lượt đoán mỗi phút (theo user, hoặc IP nếu chưa đăng nhập) → 429. `RoundTimingPolicy` từ chối lượt đến sớm hơn 0.25s (không phải người) hoặc muộn hơn 20s+5s ân hạn → 408. Nến đáp án vốn đã không nằm trong response trước khi đoán. | M |
| N3 ✅ | **Migration DB thật** | Flyway: `V1` là baseline dựng từ schema thật, `V2` đổi tên ràng buộc Hibernate tự sinh cho database cũ. `ddl-auto` giờ là `validate` — thêm field vào entity mà quên migration thì app không khởi động được, thay vì lặng lẽ sửa bảng. | S |
| N4 | **i18n vi/en** | Toàn bộ UI đang hard-code tiếng Việt. Tách file chuỗi trước khi kịp phình to. | M |
| N5 | **PWA + mobile** | Chart cảm ứng, nút LONG/SHORT cỡ ngón tay, cài về màn hình chính. Game 60 giây thì mobile là mặc định chứ không phải phụ. | M |
| N6 ✅ | **Admin nhẹ cho blog** | `/admin.html` sau `hasRole("ADMIN")`: liệt kê, tạo, sửa, xoá, ẩn/đăng bài; trình dựng khối text/ảnh. Nội dung đã chuyển từ `blog.js` vào bảng `blog_posts` (V5+V6), `blog.js` đọc `/api/blog/posts` và giữ mảng cũ làm dự phòng. | M |

### 3.4 P2 — sau khi có người dùng

- Giải đấu theo mùa, có thưởng (hợp với việc đã có wallet auth sẵn).
- Gói premium: lịch sử thống kê đầy đủ, export CSV, chế độ Trade Sim không giới hạn.
- Chế độ "mù chỉ báo → có chỉ báo": so win-rate của chính mình khi có/không có EMA, RSI.
- API công khai cho ai muốn cắm bot vào đoán (leaderboard riêng cho bot — vui và tự nhiên với tệp crypto).

---

## 4. Ai đã làm rồi (khảo sát 2026-08)

| Sản phẩm | Là gì | Học được gì / khác gì mình |
|---|---|---|
| [ChartGame.com](https://www.howthemarketworks.com/tools/websites/chartgame-com/) | Paper trading sim miễn phí, replay chart lịch sử ẩn danh, có leaderboard. Đã có app iOS. | Đối thủ gần nhất về mô hình. Nặng về mua/bán và PnL, nhẹ về giáo dục. |
| [ChartGuessr](https://chartguessr.com/) + [app](https://www.chartguessr.app/) | "Đoán nến tiếp theo", stocks/crypto/forex, streak hằng ngày, thách bạn bè, thuần price action (bỏ chỉ báo). | **Trùng ý tưởng lõi nhất.** Xác nhận thị trường có thật. Điểm mình thắng được: thư viện mẫu + giải thích sau khi đoán + phân tích bias. |
| [Chart Guess: Day Trading Game](https://apps.apple.com/us/app/chart-guess-day-trading-game/id6757345317) | Bản mobile 60 giây, chấm điểm trước khi hết giờ. | Xác nhận format ngắn + đồng hồ đếm ngược là đúng hướng (G6). |
| [Chartle](https://chartle.cc/) | Game chart hằng ngày kiểu Wordle. | Mẫu để làm R1/R2. |
| TradingView Bar Replay | Chuẩn công nghiệp cho replay, nhưng intraday là tính năng trả phí và không phải là game. | Không cạnh tranh trực tiếp; là nơi người dùng "tốt nghiệp" đi lên. |
| [ERGeorgiev/StockChartsGame](https://github.com/ERGeorgiev/StockChartsGame) | Angular + .NET, đoán lên/xuống, dùng AlphaVantage. Apache-2.0 nhưng 0 sao, 14 commit. | Chỉ để tham khảo, không lấy code. |
| [junehay/chartgame](https://github.com/junehay/chartgame) | Chart game cổ phiếu đơn giản. | Tham khảo. |
| [better-bar-replay](https://github.com/mwiarda/better-bar-replay), [stocks-trainer](https://github.com/yash-dk/stocks-trainer) | Trainer lái TradingView bằng Selenium. | Không tái sử dụng được (điều khiển trình duyệt, không phải engine). |

**Kết luận:** ý tưởng không mới, đã có ít nhất 2 sản phẩm thương mại đang chạy. Không có sản phẩm nào
**open source và hoàn chỉnh** để fork. Lợi thế của repo này là phần nội dung giáo dục tiếng Việt +
thư viện mẫu đã tự viết — nên đi tiếp theo hướng "học qua chơi", không đua tính năng terminal với ChartGame.

---

## 5. Open source tận dụng được

### 5.1 Lấy về dùng thẳng

| Thư viện | License | Dùng vào việc gì |
|---|---|---|
| [ta4j/ta4j](https://github.com/ta4j/ta4j) `org.ta4j:ta4j-core:0.24.1` | MIT, 2.5k★ | **Đáng giá nhất.** 100+ chỉ báo + có sẵn candlestick pattern indicator. Dùng cho: chỉ báo overlay (G7, P2), backtest độ khó round (R4), và kiểm chứng chéo `CandlePatternMatcher` tự viết. Bản mới yêu cầu Java 25+ — đúng bằng project. Thuần Java, không kéo theo Jackson nên không đụng vụ Jackson 3. |
| [binance/binance-public-data](https://github.com/binance/binance-public-data) | MIT (script) | Định dạng + checksum của `data.binance.vision`. Backfill zip theo tháng thay REST paging (N1). Không cần lấy code, chỉ cần theo cấu trúc URL. |
| [tradingview/lightweight-charts](https://github.com/tradingview/lightweight-charts) | Apache-2.0 | **Không dùng** (README và CLAUDE.md nói ngược, đã cũ) — mọi chart trong `static/` là SVG viết tay. Nên đây là *viết lại* chứ không phải tận dụng. Chỉ cân nhắc khi làm G5, vì Trade Sim cần đường SL/TP kéo thả. |
| [tradingview/awesome-tradingview](https://github.com/tradingview/awesome-tradingview) | — | Danh mục plugin/công cụ quanh lightweight-charts, để dò trước khi tự viết. |

### 5.2 Đọc để tham khảo thiết kế (không import)

| Repo | License | Tham khảo phần nào |
|---|---|---|
| [dylanpersonguy/OpenCharts](https://github.com/dylanpersonguy/OpenCharts) | MIT, 57★ | React nên không import thẳng, nhưng có **replay mode** và **engine paper trading** (mark-to-market, đánh giá SL/TP) — chính là G5. Đọc phần đó rồi viết lại bằng Java/JS thuần. |
| [marketcalls/openalgo](https://github.com/marketcalls/openalgo) | — | Cách tổ chức luồng dữ liệu nhiều nguồn. |
| [Quod-Financial/quantreplay](https://github.com/Quod-Financial/quantreplay) | Open source | Market simulator nghiêm túc (matching engine). Quá nặng cho game này, nhưng là tài liệu tốt nếu sau này làm order book. |

### 5.3 Nguồn dữ liệu cho G3

- **Vàng / forex:** Yahoo Finance chart endpoint (`XAUUSD=X`, `GC=F`) — `YahooFinanceClient` đã có sẵn, mở rộng là được, rẻ nhất.
- **Cổ phiếu:** vẫn Yahoo cho OHLC lịch sử; Stooq là nguồn dự phòng miễn phí.
- **Lưu ý pháp lý:** dữ liệu Binance dùng lại được cho mục đích phi thương mại; Yahoo là vùng xám nếu thương mại hoá. Trước khi bật premium (P2) phải rà lại điều khoản của từng nguồn.

### 5.4 Cân nhắc *không* dùng

- **XChange / CCXT** — chỉ cần thiết nếu đi nhiều sàn. Hiện `PriceDataProvider` một interface + `BinanceProvider` là đủ; thêm abstraction sớm chỉ tốn.
- **Fork một chart game có sẵn** — không cái nào đủ chín, và stack (Angular/.NET, React) đều lệch khỏi Spring Boot + JS thuần ở đây.

---

## 6. Rủi ro & quyết định cần chốt

| Rủi ro | Ảnh hưởng | Xử lý |
|---|---|---|
| Người chơi tra cứu được chart | Phá leaderboard *nếu* có thưởng | Chấp nhận rủi ro ở giai đoạn này (xem ghi chú mục 3.1). `roundToken` là JWT ký chứ không mã hoá: `assetId` + `startIndex` giải mã ra được, và `startIndex` là OFFSET thẳng vào bảng candles. Vá bằng JWE khi P2 tới |
| `ddl-auto: update` | Mất dữ liệu khi schema đổi | N3 trước khi có user thật |
| Dung lượng nến khi thêm khung/asset | 15m × 10 asset × 4 năm ≈ 1.4M dòng | Đo trước, cân nhắc partition theo asset; N1 giảm thời gian backfill |
| Binance chặn theo vùng | Backfill hỏng ở một số nơi deploy | Cache zip đã tải, hoặc nguồn dự phòng |
| Wallet-only login | Rào cản với người mới, và mọi user đều bình đẳng (không có role) | Cân nhắc chơi ẩn danh có điểm tạm + gắn ví sau (luồng carry-over đã có sẵn) |

**Cần quyết định trước khi code:**

1. Daily Challenge tính điểm **cùng bộ chart** hay chỉ cùng seed asset/khung?
2. Bỏ luôn practice khỏi leaderboard, hay giữ bảng riêng có mẫu số tối thiểu?
3. Trade Sim (G5) là chế độ riêng hay thay thế luôn cách chơi hiện tại?

---

## 7. Thứ tự đề xuất

**Sprint 1 — nền móng:** N3, N2, G6, G1
**Sprint 2 — lý do quay lại:** R1, R2, R3
**Sprint 3 — chiều sâu:** G2, N1, G7, R7
**Sprint 4 — mở rộng:** G3, G4, R5, R6
**Sau đó:** G5, R4, P2
