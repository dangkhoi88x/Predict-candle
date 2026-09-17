# Candles — review tổng thể và kế hoạch MVP

> **Tiến độ (cập nhật 2026-09-14):** MVP 0 đã xong và đang chạy trên bản live (PR #60–#63), kèm
> backup database (§6). **MVP 1** (M1.1–M1.3) đã làm ở PR #65–#67, bắt đầu trước khi đủ điều kiện
> theo quyết định của chủ project.
>
> Viết 2026-09-14, ngay sau khi bản demo lên <https://candles-oj1q.onrender.com>. Thay mục 7
> ("Thứ tự đề xuất") của `SPEC.md`: phần lớn backlog ở đó đã làm xong, và câu hỏi bây giờ không
> còn là *làm thêm gì* mà là *làm gì để có người chơi thật và biết họ có quay lại không*.

## 0. Tóm tắt

- **Tính năng đã vượt đối thủ trực tiếp.** Daily challenge, streak, badge, leaderboard, gợi ý khi
  sai, quiz mẫu nến, live round, paper trading, trang admin: đối thủ gần nhất có khoảng một nửa
  số đó.
- **Chưa có người chơi nào, và chưa đo được gì trước lúc người ta bấm đoán.** Không có analytics,
  không có onboarding. Link chia sẻ không có ảnh xem trước, trong khi daily challenge dựa hẳn vào
  việc được chia sẻ.
- **Vì vậy MVP tiếp theo không phải tính năng mới.** Nó là: đo được → người mới hiểu trong 10 giây
  → chia sẻ ra được → mang tới đúng chỗ người chơi crypto Việt đang ở. Tính năng mới chỉ làm sau
  khi số liệu cho thấy người chơi quay lại.
- **Điểm khác biệt nên dồn sức vào: "chơi → sai → học đúng chỗ sai" bằng tiếng Việt.** Nó đã có
  một nửa (gợi ý, quiz, thư viện mẫu). Nửa còn thiếu là *phân tích thiên kiến*, thứ `SPEC.md`
  gọi là đáng tiền nhất nhưng chưa ai làm.

---

## 1. Hiện trạng thật (2026-09-14)

175 commit trong 17 ngày làm việc (28/08 → 14/09), bản demo chạy trên Render + Neon, nguồn giá OKX.

| Mảng | Có gì |
|---|---|
| Game | Practice (5 lượt/chart, đồng hồ 20 giây), **daily challenge** + archive 60 ngày + chia sẻ kiểu Wordle, **gợi ý tăng dần** khi sai (volume → MA → tên mẫu), chart bối cảnh + chip mẫu nến sau vòng |
| Chơi realtime | **Live round** trên nến 1h đang chạy, pool LONG/SHORT, roster, lịch sử + popup replay |
| Học | Thư viện mẫu nến, mẫu hình kỹ thuật (có "tìm ví dụ thật"), tâm lý giao dịch, blog (Tiptap), **quiz mẫu nến mỗi ngày** |
| Giữ chân | Streak ngày chơi, streak daily, 9 badge, leaderboard công khai, hồ sơ |
| Trading | **Paper trading** (phí 10 bps, MA/RSI, crosshair, 5 khung) |
| Admin | 10 pane: tổng quan, retention, người chơi + drill-down, live round (xoá vòng), demo, preview thử thách, blog, nội dung, media, asset |
| Nền tảng | Đăng nhập bằng ví (Reown có sẵn email/Google/X/Discord/GitHub), Flyway 17 migration, 26 controller / 65 endpoint, 43 lớp test / 260 test, CI |

**So với `SPEC.md` (31/08):** G1 G6 G7 · R1 R2 R3 R5 · N2 N3 N6 đã xong, cộng thêm live round,
paper trading, quiz, archive, gợi ý và admin, là những thứ spec chưa hề có.
**Chưa làm:** G2 (đa khung cho game), G3 (đa tài sản), G4 (mức tự tin), G5 (trade sim có SL/TP),
R4 (Elo), R6 (thách đấu), **R7 (phân tích thiên kiến)**, N1, N4 (i18n), N5 (PWA).

---

## 2. Review tổng thể

### 2.1 Làm tốt, nên giữ

- **Tính công bằng nằm ở server.** Đáp án ký trong token, thời gian đo từ `iatMs`, một lượt mỗi ngày
  do unique constraint giữ, client không bao giờ gửi giá. Đây là nền cho mọi thứ có xếp hạng
  về sau.
- **Số liệu được tính ra từ lịch sử, không lưu riêng.** Streak, badge, số dư demo, retention đều
  suy từ bảng gốc, nên không có số nào trôi lệch khỏi thực tế.
- **Tài liệu quyết định dày và đúng chỗ.** CLAUDE.md giải thích *vì sao* chứ không chỉ *là gì*,
  nên người sau sửa code ít phá nhầm.
- **Có đường chẩn đoán từ ngoài vào.** Lỗi gọi sàn trả 502 kèm lý do (xem `DEPLOY_PLAN.md` §2).

### 2.2 Vấn đề sản phẩm — quan trọng hơn mọi vấn đề kỹ thuật

| # | Vấn đề | Vì sao đáng lo |
|---|---|---|
| P1 | **Không đo được phễu.** Retention trong admin chỉ bắt đầu từ *lượt đoán đầu tiên*. Không biết bao nhiêu người mở trang, bao nhiêu bỏ đi trước khi đoán, bao nhiêu kết nối ví | Không biết vấn đề nằm ở đâu thì mọi tính năng mới đều là đoán mò |
| P2 | **Không có onboarding.** Người mới vào gặp ngay chart, hai nút và rail 11 mục. Không có dòng nào nói luật chơi, điểm tính thế nào, vì sao nên đăng nhập | Game 60 giây mất người trong 10 giây đầu |
| P3 | **Chia sẻ không có ảnh xem trước.** Nội dung chia sẻ có link, nhưng `index.html` không có `og:*` hay `description`. Dán vào Zalo/Facebook/Telegram chỉ ra một link trơn | R1 + R2 là vòng lan truyền chính, và nó đang bị hỏng ở bước cuối |
| P4 | **Quá nhiều bề mặt so với số người dùng (0).** 11 view + 10 pane admin. Mỗi thứ mới đều phải giữ cho khỏi hỏng | Chi phí bảo trì đang đi trước lợi ích |
| P5 | **Cold start.** Render free ngủ (đã có cron), Neon tắt compute (lượt đầu mất 4,4 giây, sau đó 0,3 giây) | Người đầu tiên sau khoảng lặng thấy trang chậm |
| P6 | **SEO gần như bằng 0.** Blog, thư viện mẫu, tâm lý đều là tab trong một trang, không có URL riêng | Nội dung tiếng Việt là lợi thế lớn nhất nhưng Google không tìm thấy |

### 2.3 Nợ kỹ thuật — biết để không bị bất ngờ, chưa cần làm hết

| Nợ | Mức | Ghi chú |
|---|---|---|
| `README.md` sai với thực tế (lightweight-charts, 4 nến, localStorage, 2 asset) | Thấp, sửa nhanh | Người mới đọc README đầu tiên |
| Một instance, nhiều thứ nằm trong bộ nhớ (rate limit, cache, cooldown sàn, job sync) | Chỉ quan trọng khi scale | Đã ghi trong `DEPLOY_PLAN.md` §7 |
| Không có error monitoring | Trung bình | Log Render khó đọc (đã trả giá một buổi tối) |
| `roundToken` là JWS, đọc được payload | Thấp đến khi có thưởng | `SPEC.md` §6 đã chốt: làm JWE khi có giải có thưởng |
| Không có backup ngoài Neon | Thấp cho demo | Nến lấy lại được, tài khoản thì không |
| Toàn bộ UI hard-code tiếng Việt | Chỉ quan trọng nếu ra quốc tế | Xem quyết định Q1 |

### 2.4 Pháp lý — ranh giới cần giữ

Việt Nam đang thí điểm thị trường tài sản mã hoá có quản lý. Game **không có tiền thật** (điểm,
badge, paper trading) nằm ngoài mọi vùng xám. Ranh giới là: **không** giải thưởng bằng tiền hay
token cho kết quả đoán, **không** nạp hay rút, **không** gọi paper trading là "đầu tư". Vượt qua
một trong ba điều đó là cờ bạc hoặc tư vấn tài chính, và cần luật sư trước khi code.

---

## 3. Research

### 3.1 Đối thủ (khảo sát lại 2026-09)

| Sản phẩm | Có gì | So với Candles |
|---|---|---|
| [One Candle Ahead](https://play.google.com/store/apps/details?id=com.onecandle.ahead) | Đoán nến trong 30 giây, tiền ảo, **hạng trader**, daily challenge, **đấu 1v1**, leaderboard | Có đấu 1v1 và hạng, Candles chưa có. Không có phần học |
| [Chart Guess](https://apps.apple.com/us/app/chart-guess-day-trading-game/id6757345317) | Game phản xạ BUY/SELL có đồng hồ, thư viện **50+ mẫu hình** | Thư viện rộng hơn. Không có live, daily hay gợi ý |
| [ChartGuessr](https://www.chartguessr.app/guides/games-that-teach-trading/) | Stocks/crypto/forex, XP, badge, daily streak, daily challenge cùng chart, **thống kê mẫu nào có lãi** | Gần nhất về ý tưởng. Có thống kê theo mẫu, Candles chưa có |
| [Candle Master](https://play.google.com/store/apps/details?id=com.candlemaster.tradingquiz&hl=en_SG) | Đoán nến + quiz + chỉ báo RSI/MACD/MA | Tương tự quiz + gợi ý của Candles |
| [Chartle](https://chartle.cc/) | Một chart mỗi ngày kiểu Wordle | Candles đã có daily + archive |

**Kết luận:**
- **Không còn thiếu tính năng cơ bản nào so với đối thủ.** Hai thứ họ có mà mình chưa có là
  **đấu 1v1** và **thống kê theo mẫu / thiên kiến**.
- **Tất cả đối thủ là app tiếng Anh trên store.** Chưa ai làm bản tiếng Việt, chưa ai có live
  round chung cả cộng đồng, chưa ai nối được "sai → học đúng mẫu vừa sai".
- **ChartGuessr tự định vị** là game học giao dịch tốt vì phản hồi tính bằng giây và lặp nhiều
  lần. Đây đúng là thứ Candles đang làm.

### 3.2 Người chơi ở đâu

- **Thị trường:** khoảng [17–21 triệu người Việt](https://reports.tiger-research.com/p/2025-vietnam-web3-market-report-eng)
  đã từng dùng crypto. Vietnam đứng thứ 4 trong chỉ số chấp nhận crypto của Chainalysis 2025. Gần
  như toàn bộ là nhà đầu tư cá nhân, dùng [Binance, Bybit, OKX](https://blog.investvietnam.co/the-state-of-crypto-trading-in-vietnam-in-2026/).
  Đây là tệp đúng, đông, và nói tiếng Việt.
- **Kênh:** cộng đồng crypto Việt sống trên **Telegram**, Facebook group và Zalo. Telegram Mini App
  chạy được chính web app hiện tại bên trong Telegram. Các bài tổng hợp của ngành
  ([ChainPeak](https://medium.com/@chainpeak/2026-telegram-mini-app-marketing-complete-guide-how-ton-ecosystem-projects-go-from-0-to-1m-users-61eb4f752b8d),
  [Dailygame](https://www.dailygame.net/telegram-mini-apps-and-the-new-wave-of-bot-based-games-how-ton-gaming-reshaped-mobile-play-in-2026/))
  nói chi phí có người dùng thấp hơn app store rất nhiều, và retention 7 ngày 30–50%. Các con số
  này do bên làm dịch vụ đưa ra, **nên coi là trần lạc quan chứ không phải kỳ vọng**. Nhưng hướng
  đi là đúng: đưa game tới chỗ có sẵn nhóm chat, thay vì chờ người ta gõ domain.

---

## 4. Định nghĩa MVP

**Người chơi mục tiêu:** người Việt đã có tài khoản sàn, trade theo cảm tính, muốn biết mình đọc
chart giỏi thật hay chỉ may. Chơi trên điện thoại, trong lúc chờ.

**Vòng lõi:** mở link trong nhóm chat → chơi daily trong 60 giây → thấy mình sai ở đâu → chia sẻ
kết quả → mai quay lại.

**Chỉ số quyết định** (đo từ MVP 0 trở đi):

| Chỉ số | Định nghĩa | Ngưỡng để làm MVP kế tiếp |
|---|---|---|
| Phễu vào | mở trang → đoán lượt đầu → hết một chart | ≥ 50% người mở trang đoán ít nhất một lượt |
| Kích hoạt | người đoán → đăng nhập | ≥ 15% |
| Quay lại ngày sau | cohort người chơi lần đầu → quay lại hôm sau | ≥ 20% |
| Quay lại trong tuần | cohort → quay lại trong 7 ngày | ≥ 30% |
| Chia sẻ | người hết daily → bấm chia sẻ | ≥ 10% |

Hai chỉ số quay lại đã có trong `/api/admin/retention`, chỉ số chia sẻ và phễu vào thì chưa. Các
ngưỡng là điểm xuất phát để tranh luận, **không phải số đã kiểm chứng**. Sau hai tuần có dữ liệu
thật thì chỉnh lại.

---

## 5. Các MVP

Ký hiệu effort như `SPEC.md`: S ≤ 1 ngày, M ≤ 3 ngày, L ≥ 1 tuần.

### MVP 0 — Đo được, hiểu được, chia sẻ được · khoảng 1 tuần

Không thêm chế độ chơi nào. Làm cho những gì đã có hoạt động được với người lạ.

| # | Việc | Effort | Xong khi |
|---|---|---|---|
| M0.1 ✅ | **Đo phễu.** Ghi các mốc *mở trang / đoán lượt đầu / hết chart / bấm chia sẻ / đăng nhập*. Hai cách: thêm GoatCounter hoặc Umami (một thẻ script, miễn phí, không cookie), hoặc tự ghi vào bảng `events` rồi hiện trên pane Tổng quan. **Nghiêng về công cụ ngoài:** CLAUDE.md phản đối bảng events vì dữ liệu chơi đã có sẵn, nhưng lượt *mở trang* thì không nằm ở bảng nào | S | Admin trả lời được "hôm qua bao nhiêu người mở trang, bao nhiêu đoán" |
| M0.2 ✅ | **Thẻ meta chia sẻ.** `description`, `og:title/description/image`, `twitter:card`, ảnh 1200×630 tĩnh | S | Dán link vào Zalo và Telegram hiện được thẻ có ảnh |
| M0.3 ✅ | **Onboarding 3 bước** cho lượt truy cập đầu (cờ trong localStorage): luật chơi, điểm và streak, vì sao nên đăng nhập. Bỏ qua được, không chặn chart | M | Người chưa từng nghe về game chơi được mà không phải hỏi |
| M0.4 ↺ | **Mặc định vào Daily cho người mới**, thay cho Practice. Daily có đích rõ (5 lượt, một lần mỗi ngày, có nút chia sẻ); Practice vô tận không có điểm dừng | S | Lượt vào đầu tiên hạ cánh ở tab Hôm nay |
| M0.5 ✓ | **Rút gọn rail cho khách chưa đăng nhập:** giấu Trade và Hồ sơ, dồn các tab học vào nhóm đóng sẵn (đã có). Người đã đăng nhập vẫn thấy đủ | S | Người lạ thấy tối đa 6 mục |
| M0.6 ✅ | **Sửa README** cho khớp thực tế, kèm link demo | S | — |
| M0.7 ✅ | **Error monitoring.** Sentry free cho Java và JS, hoặc tối thiểu một pane admin hiện 50 lỗi gần nhất | S | Lỗi 5xx trên bản live tự báo, không cần ai đọc log |

**Đã làm thế nào, và chỗ nào khác plan** (✅ xong như plan · ↺ đổi cách làm · ✓ không cần làm):

- **M0.1** — GoatCounter, không cookie. `analytics.js` gửi các mốc `onboarding-*`, `first-guess`,
  `chart-complete`, `daily-first-guess`, `daily-complete`, `daily-share`, `sign-in`; bật bằng
  biến `ANALYTICS_GOATCOUNTER` (mã site, không phải URL). Đọc phễu bằng cách chia số lượt của
  bước sau cho bước trước trên dashboard GoatCounter.
- **M0.2** — ảnh `og-image.png` được dựng từ `web/og/og-image.html` bằng Chrome headless, dùng
  font và màu của app.
- **M0.3** — onboarding hiện **trước** khi chia chart đầu tiên, không đè lên chart. Đồng hồ 20
  giây bắt đầu từ lúc server phát token, nên đè lên chart là đốt mất lượt đầu của người đang đọc
  hướng dẫn.
- **M0.4 ↺** — không đưa người mới thẳng vào Daily. Daily cũng chạy đồng hồ ngay khi mở, và mỗi
  ngày chỉ có một lượt: vào thẳng đó là tiêu lượt duy nhất trong ngày của người còn đang làm quen.
  Thay vào đó bước cuối của onboarding cho chọn "Chơi thử ngay" (nút chính) hoặc "Thử thách hôm
  nay".
- **M0.5 ✓** — khách chưa đăng nhập vốn chỉ thấy 6 mục trên rail (nhóm Học đóng sẵn, Hồ sơ ẩn).
- **M0.7** — không dùng Sentry. Bảng "Lỗi gần đây" trên pane Vận hành, giữ 50 lỗi mới nhất trong
  bộ nhớ: không cần tài khoản ngoài, hợp với mục tiêu học và làm portfolio.

**Chưa làm MVP 1 cho tới khi** có ít nhất 50 người chơi thật (mời bạn bè, một nhóm Telegram, một
bài Facebook) và một tuần số liệu.

### MVP 1 — Học đúng chỗ sai · khoảng 2 tuần

Đẩy mạnh điểm khác biệt duy nhất đối thủ chưa có.

| # | Việc | Effort | Ghi chú |
|---|---|---|---|
| M1.1 ✅ | **Phân tích thiên kiến (R7).** Trên hồ sơ: "Bạn chọn LONG 68% số lượt, thị trường chỉ tăng 51%", độ chính xác theo cặp, theo giờ, theo tăng/giảm, theo tình huống chart vừa tăng mạnh hay vừa giảm mạnh | M | Suy từ `guess_results` như `PlayStreak`, không lưu thêm. Cần mẫu số tối thiểu (khoảng 30 lượt) trước khi kết luận |
| M1.2 ✅ | **Thống kê theo mẫu nến.** "Khi có Hammer trên chart, bạn đoán đúng 41%", mỗi mẫu có link sang thẻ trong thư viện | M | `RoundPatternScanner` đã quét mẫu cho chart bối cảnh; cần quét cửa sổ từng lượt đoán. ChartGuessr có tính năng này |
| M1.3 ✅ | **Thẻ "Bài học hôm nay" sau daily:** mẫu hoặc thiên kiến lớn nhất vừa lộ ra, một câu và một link | S | Nối daily vào thư viện, khép vòng chơi → sai → học |
| M1.4 — | **Mức tự tin (G4)** 1×/2×/3× | M | *Tuỳ chọn.* Đo được hiệu chỉnh, nhưng thêm một quyết định cho mỗi lượt đoán. Chỉ làm nếu số liệu MVP 0 cho thấy người chơi chơi nhiều lượt mỗi phiên |

**Đã làm thế nào** (bắt đầu 2026-09-14, trước khi đủ điều kiện ở cuối MVP 0, theo quyết định
của chủ project vì mục tiêu là học và làm portfolio):

- **M1.1** — mục "Thói quen khi đoán" trên Hồ sơ, từ `GET /api/stats/me/insights`. Đọc 500 lượt
  gần nhất, nối từng lượt với đúng các nến người chơi đã thấy. Nhận xét: nghiêng LONG/SHORT, đoán
  kém sau nhịp tăng / nhịp giảm / khi đi ngang (nhịp được đo bằng biên độ nến, không bằng %), kém
  theo buổi (giờ Việt Nam), hay để hết giờ. Không kết luận dưới 30 lượt bấm, trừ nhận xét hết giờ.
- **M1.2** — bảng theo mẫu nến ngay trước lượt đoán, mỗi tên mẫu mở thẻ trong thư viện; nhận xét
  `WEAK_PATTERN` khi một mẫu kém hơn mức chung từ 10 điểm trên ít nhất 10 lượt.
- **M1.3** — thẻ "Bài học hôm nay" sau daily, ưu tiên: mẫu nến ngay trước một nến đoán sai →
  thói quen lớn nhất → mẫu nến có trên chart → lời mời đăng nhập hoặc chơi thêm. Chạy cả khi chưa
  đăng nhập; bấm nút trên thẻ được đếm là `daily-lesson-click`.
- **M1.4** — chưa làm, đúng như plan ghi là tuỳ chọn: thêm một quyết định cho mỗi lượt đoán, và
  chưa có số liệu cho thấy người chơi chơi nhiều lượt mỗi phiên.

**Chỉ số của MVP 1:** tỉ lệ người quay lại trong tuần ở nhóm đã xem trang thiên kiến so với nhóm
chưa xem.

### MVP 2 — Đi tới chỗ người chơi · khoảng 2 tuần

| # | Việc | Effort | Ghi chú |
|---|---|---|---|
| M2.1 ✅ | **Telegram Mini App.** Bọc web app hiện tại qua `@BotFather`, dùng Telegram WebApp SDK cho theme và nút chính | M | Chạy chính `index.html`. Việc khó là đăng nhập (M2.2) |
| M2.2 ✅ | **Đăng nhập bằng Telegram** (`initData` ký HMAC bằng bot token), song song với ví | M | Bỏ bước ký ví cho người vào từ Telegram. Không cần cột mới: tài khoản Telegram có khoá `tg:<id>` trong cột `wallet_address`, nên không bao giờ trùng ví admin. Cần tạo bot, xem DEPLOY_PLAN §4.4 |
| M2.3 ✅ | **Thách đấu qua link (R6).** Link chứa seed, người nhận chơi đúng bộ chart đó, hai bên so điểm | M | Dùng lại cơ chế chart theo seed của daily. Đối thủ có "duel", mình chưa có. Hợp để thả vào nhóm chat |
| M2.4 ✅ | **Bot nhắc daily trong nhóm:** 8h sáng gửi "Thử thách #257 đã mở", tối gửi top 3 | S | Chỉ khi M2.1 xong và nhóm đồng ý |
| M2.5 ✅ | **PWA** (manifest + icon), cài được lên màn hình chính | S | Rẻ, dành cho người không dùng Telegram |

### MVP 3 — Giữ chân dài hạn · làm khi có số liệu ủng hộ

| # | Việc | Điều kiện để bắt đầu |
|---|---|---|
| M3.1 ✅ | **Mùa giải không có thưởng tiền:** bảng xếp hạng reset theo tháng, huy hiệu mùa | Quay lại trong tuần ≥ 30% |
| M3.2 ⏳ | **Đa khung cho game (G2):** 4h và 1d gộp từ nến 1h (`CandleAggregator` đã có), 15m thì cần lưu thêm | Người chơi lâu năm phàn nàn chart nhàm |
| M3.3 | **Độ khó theo cộng đồng (R4):** tỉ lệ đoán đúng mỗi chart → điểm thưởng | Mỗi chart daily có ≥ 30 người chơi |
| M3.4 ✅ | **Trang nội dung có URL riêng** cho blog và từng mẫu (render phía server hoặc prerender), phục vụ SEO | Muốn có lượng truy cập tự nhiên từ Google |

**Đang làm** (bắt đầu 2026-09-17, chưa đạt điều kiện số liệu, theo quyết định của chủ project vì
mục tiêu là học và làm portfolio — giống MVP 1):

- **M3.1** — bảng xếp hạng theo tháng (`Season`, một tháng UTC). `?season=2026-09` cho một tháng,
  `?season=all` cho mọi lúc, không truyền gì thì là tháng đang chạy. Không reset và không lưu gì
  thêm: mùa chỉ là bộ lọc trên chính các lượt đã ghi, nên tháng cũ đọc lại vẫn đúng như lúc đó.
  Thẻ top bên cạnh chart, tag ở rail và huy hiệu hạng trên hồ sơ đều theo tháng đang chạy.
  Huy hiệu mùa: `GET /api/leaderboard/seasons` trả podium của từng tháng đã kết thúc và huy hiệu
  của chính người gọi. Không lưu gì khi tháng đóng — huy hiệu là thứ hạng của tháng đó hỏi lại,
  đọc từ đúng bảng đã cache, nên huy hiệu và bảng không thể lệch nhau. Hồ sơ hiện huy hiệu của
  người chơi, bảng xếp hạng hiện podium tháng trước ngay dưới bộ chọn mùa.
- **M3.4** — mỗi bài blog đã đăng có một trang riêng render sẵn ở máy chủ (`/blog/<slug>`), kèm
  trang danh sách `/blog` và `sitemap.xml`; `robots.txt` trỏ tới sitemap. Trang không tải app,
  canonical trỏ về chính nó, và có link mở lại bài đó trong app. Mỗi mẫu nến, mẫu hình và ghi chú
  tâm lý cũng có trang riêng (`/mau-nen/<key>`, `/mau-hinh/<key>`, `/tam-ly/<key>`) kèm trang danh
  sách; trang mẫu chỉ có chữ, còn hình thì bấm sang đúng thẻ trong app. Sitemap liệt kê tất cả.

### Cố ý chưa làm

- **Giải thưởng tiền hoặc token, premium, thanh toán.** Pháp lý (§2.4), và chưa có người dùng để
  biết họ trả tiền cho cái gì.
- **Thêm tài sản (vàng, cổ phiếu — G3).** Nguồn dữ liệu Yahoo là vùng xám, và tệp crypto Việt chưa
  cần.
- **Trade Sim có SL/TP (G5).** Paper trading đã phủ nhu cầu "thử vào lệnh", còn G5 là module lớn.
- **i18n / tiếng Anh.** Đối thủ đã chiếm thị trường tiếng Anh. Tiếng Việt là lợi thế, không phải
  thiếu sót. Xem lại nếu Q1 đổi.
- **Scale ngang, trả tiền hạ tầng.** Nâng Render hoặc Neon chỉ khi có ngày cold start hoặc hết
  compute hours thật sự làm mất người.

---

## 6. Việc kỹ thuật chạy song song

| Việc | Khi nào |
|---|---|
| ✅ Export Neon định kỳ: `.github/workflows/backup.yml`, dump mã hoá mỗi đêm, giữ 30 ngày | Trước khi mời người chơi thật: tài khoản mất là mất |
| Theo dõi compute hours Neon và giờ free Render | Mỗi tuần một lần trong tháng đầu |
| ✅ Ghi chú trên admin rằng giá đang lấy từ OKX hay Binance (pane Vận hành) | Cùng M0.6 |
| Giữ CI xanh, không merge đỏ (`autoDeployTrigger: checksPass` đang dựa vào điều này) | Luôn luôn |

---

## 7. Cần chốt trước khi bắt đầu MVP 0

| # | Câu hỏi | Đề xuất |
|---|---|---|
| Q1 | Thị trường: **Việt Nam trước**, hay quốc tế? | Việt Nam. Nội dung, cộng đồng và lợi thế cạnh tranh đều ở đây |
| Q2 | Kênh đầu tiên: **Telegram Mini App** hay web + Facebook? | Web + một nhóm Telegram ở MVP 0 để có số liệu, Mini App ở MVP 2 khi phễu đã sạch |
| Q3 | Đo phễu bằng **công cụ ngoài** hay **bảng events tự làm**? | Công cụ ngoài (GoatCounter hoặc Umami). Rẻ, không thêm migration, tắt được |
| Q4 | Mục tiêu của project: **học / portfolio** hay **thành sản phẩm có doanh thu**? | Quyết định việc có bao giờ làm §5 "Cố ý chưa làm" hay không. Kế hoạch này hợp với cả hai cho tới hết MVP 2 |
