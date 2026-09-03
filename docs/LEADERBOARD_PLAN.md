# Bảng xếp hạng — kế hoạch

> Nháp 2026-09-03, **đã dựng xong giai đoạn 1**. Xếp hạng theo `score`, công khai cho tất cả.
> Ngưỡng 20 lượt. Giai đoạn 2 (ghi sẵn vào cột) chưa làm — xem §4 để biết mốc nên đổi.

## 1. Đang có sẵn những gì

Gần như đủ nguyên liệu, chỉ thiếu chỗ ghép lại:

| | |
|---|---|
| Dữ liệu | `guess_results` — mỗi lượt đoán đã trả lời của người đã đăng nhập. Chơi ẩn danh không ghi gì. |
| Cách tính điểm | `PlayerScore.of(flagsInPlayOrder)` — 10 điểm/lượt đúng, +2 mỗi bậc chuỗi, trần +20. |
| Truy vấn sẵn | `GuessResultRepository.tallyByUser()` → `[userId, total, correct, lastPlayed]`, đang dùng cho bảng người chơi bên admin. |
| Index | `(user_id, created_at)` — đúng thứ tự một phép gấp theo từng người cần. |
| Danh tính | `users.display_name`, mặc định là ví rút gọn (`0x6ee0…3564`). |

## 2. Quyết định quan trọng nhất: **không xếp hạng theo số liệu tự khai**

`users` giữ bốn cột `legacy_*` — tally mà trình duyệt tự đếm trước khi người chơi có tài khoản,
được gộp vào một lần qua `POST /api/stats/legacy`. **Toàn bộ những con số đó do client gửi
lên.** `isCoherent()` chỉ chặn thứ vô lý (`correct <= total`, `score <= correct * 20`), không
chặn được một lời khai cao vừa phải mà hợp lệ.

Chính `StatsService` đã ghi sẵn điều này:

> *"That does not make an inflated-but-plausible claim impossible, which is why the response
> keeps recorded totals separate for anything that ranks players."*

Không phải chuyện lý thuyết. Trên dữ liệu hiện tại, tài khoản duy nhất có import:

```
0x6ee0…3564    tự khai 88 lượt / 512 điểm    máy chủ ghi 102 lượt
```

Gần một nửa con số hiển thị là tự khai. Một bảng xếp hạng cộng cả `legacy_*` là một bảng mà
cách leo hạng nhanh nhất là gọi thẳng API import, không phải chơi.

**Nên: xếp hạng chỉ trên `recorded`** — phần máy chủ tự chấm. `StatsResponse` đã tách sẵn
`recorded` khỏi tổng, nên chỗ này chỉ là dùng đúng cái đã có. Hồ sơ cá nhân vẫn hiện tổng gồm
legacy như hiện tại; bảng xếp hạng thì không.

## 3. Xếp theo cột nào

Ba lựa chọn, thưởng cho ba kiểu chơi khác nhau:

| Cột | Thưởng cho | Vấn đề |
|---|---|---|
| **Điểm** (`score`) | Vừa đúng nhiều vừa chơi nhiều; chuỗi được thưởng | Ai chơi lâu hơn gần như luôn thắng — bảng thành bảng chuyên cần |
| **Tỉ lệ đúng** | Đoán giỏi thật | Vô nghĩa nếu không có ngưỡng tối thiểu: 3/3 = 100% đứng trên 700/1200 |
| **Số lượt đúng** | Dễ hiểu nhất | Vẫn là bảng chuyên cần, lại bỏ mất phần chuỗi |

**Đề xuất: xếp theo `score`, kèm ngưỡng tối thiểu, và hiện cả ba cột.** Điểm đã là con số
game này vốn dùng (`app.js` và hồ sơ đều hiện nó), chuỗi đã nằm trong công thức, nên không phải
phát minh thêm thước đo mới. Ngưỡng (đề xuất **≥ 20 lượt đã ghi**) để một tài khoản mới đúng 2
lượt không nhảy lên đầu bảng.

Hiện cả ba cột là điều đáng làm dù xếp theo gì: người đọc tự thấy ai giỏi và ai chỉ chăm.

## 4. Tính lúc đọc, hay ghi sẵn vào cột

Chỗ khó: `score` và `bestStreak` **phụ thuộc thứ tự** — phải duyệt lịch sử của từng người theo
thời gian, không gộp được bằng `SUM`/`COUNT` thuần.

**Giai đoạn 1 — tính lúc đọc, cache 60 giây.** Một truy vấn duy nhất:

```sql
select user_id, correct from guess_results order by user_id, created_at
```

đi đúng index `(user_id, created_at)` sẵn có, rồi gấp trong Java bằng `PlayerScore.of` cho từng
người. Cùng khuôn mẫu Caffeine 60s như `AdminStatsService`, kèm `?fresh=true` như đã làm ở đó.
Không thêm cột, không thêm đường ghi, không có gì để lệch.

**Giai đoạn 2 — ghi sẵn, khi nào cần.** Khi bảng bắt đầu chậm, thêm `users.recorded_score`,
`recorded_total`, `recorded_correct`, `recorded_best_streak`, cập nhật ngay trong
`GuessResultService.record()` (đúng một chỗ ghi duy nhất trong app), và bảng xếp hạng thành
`ORDER BY` trên index. Kèm một job/endpoint tính lại toàn bộ để sửa khi lệch.

**Mốc để đổi:** khi `guess_results` vượt ~500k dòng, hoặc khi thời gian trả lời của endpoint
vượt ~200ms lúc cache miss. Hiện có 146 dòng, nên giai đoạn 1 thừa sức và giai đoạn 2 lúc này
là tối ưu hoá sớm.

## 5. API

```
GET /api/leaderboard?limit=50
```

```json
{
  "generatedAt": "2026-09-03T00:40:00Z",
  "minGuesses": 20,
  "rows": [
    { "rank": 1, "displayName": "0x6ee0…3564", "score": 642,
      "total": 102, "correct": 38, "accuracy": 0.372, "bestStreak": 5 }
  ],
  "me": { "rank": 7, "displayName": "…", "score": 120, "…": "…" }
}
```

- **`me` là thứ khiến bảng đáng mở lần thứ hai.** Chỉ có top 50 thì người hạng 80 mở một lần
  rồi thôi; biết mình đứng đâu mới là lý do quay lại. Trả `null` khi chưa đăng nhập hoặc chưa
  đủ ngưỡng.
- Không trả `walletAddress`, chỉ `displayName`. Xem §6.
- Công khai hay bắt đăng nhập: xem câu hỏi còn mở.

## 6. Riêng tư

Đây là lần đầu app **công bố dữ liệu của người này cho người khác xem**, nên đáng dừng lại một
nhịp.

- **Chỉ `displayName`, không bao giờ `walletAddress`.** Mặc định `displayName` là ví rút gọn
  (`0x6ee0…3564`), đủ để không lộ địa chỉ đầy đủ. Nhưng người dùng đổi được tên hiển thị, nên
  cần nhớ: tên đó giờ là tên công khai.
- **Có nên xin phép không?** Bảng xếp hạng bút danh của một trò chơi thì thông lệ là mặc định
  bật. Nếu muốn chắc, thêm `users.leaderboard_opt_out` và một ô tắt trong Hồ Sơ — rẻ, và trả
  lời dứt điểm câu "sao tên tôi ở đây".
- **Không xếp hạng ADMIN** — hoặc ít nhất đánh dấu. Tài khoản admin chơi để kiểm thử, đứng đầu
  bảng bằng dữ liệu test thì bảng mất nghĩa.

## 7. Chống lạm dụng

Phần lớn đã có sẵn, đáng ghi ra để khỏi tưởng là chưa:

- Ràng buộc `(user_id, asset_id, timeframe, start_index, guess_number)` khiến phát lại
  `roundToken` không tính thêm lần nữa — đúng thứ chặn cách gian lận hiển nhiên nhất.
- `roundToken` là JWT máy chủ ký, giữ sẵn đáp án; client không tự khai kết quả.
- `RateLimiter` đã giới hạn `guessesPerMinute` = 30.
- **Còn thiếu:** `/api/leaderboard` chưa có rate limit. Endpoint này đắt hơn hẳn phần còn lại
  và ai cũng gọi được — nên cho vào `RateLimiter`, và tiện thể xử luôn món nợ `/api/admin/**`
  mà `ADMIN_PLAN.md` §4 vẫn ghi là sẽ làm.

## 8. Frontend

Một tab mới, hay một khối trong Hồ Sơ?

**Đề xuất: tab mới `Bảng Xếp Hạng`**, dựng theo `onFirstShow` trong `nav.js` như heatmap và
blog — bảng chỉ tải khi có người mở, không tốn gì của người vào chơi.

- Dùng lại `.ops-table`? Không — đó là lớp của trang admin. Trang game có ngôn ngữ riêng; bảng
  này nên theo `--panel`/`--border` của trang game.
- Số dùng `font-variant-numeric: tabular-nums` và `CandleRolling` cho cột điểm, đúng quy ước
  đang có.
- Hàng của chính mình được đánh dấu và ghim lại nếu nằm ngoài top 50.
- Trạng thái rỗng và lỗi đi qua `CandleContent.notice`, giống bốn tab kia sau lần dọn dự phòng.

## 9. Thứ tự thực hiện

1. `LeaderboardService` + `GET /api/leaderboard`, tính lúc đọc, cache 60s, cho vào `RateLimiter`.
2. Test: xếp đúng thứ tự; ngưỡng tối thiểu chặn tài khoản ít lượt; **`legacy_*` không ảnh hưởng
   thứ hạng** (test này là cái quan trọng nhất); `me` đúng cả khi ngoài top.
3. Tab frontend + trạng thái rỗng/lỗi.
4. Ô tắt riêng tư, nếu chọn làm.
5. Ghi sẵn vào cột — chỉ khi chạm mốc ở §4.

## 10. Đã chốt và còn mở

Đã chốt: xếp theo **`score`**, **công khai** cho tất cả, ngưỡng **20 lượt**.

Còn mở:

1. **Ô tắt riêng tư** — chưa làm. Bút danh mặc định là ví rút gọn nên chưa lộ gì, nhưng người
   dùng đổi được tên hiển thị và tên đó giờ là tên công khai.
2. **Ẩn tài khoản ADMIN** — chưa làm, và hiện *không nên*: tài khoản admin là tài khoản duy
   nhất đủ 20 lượt, ẩn đi là bảng trống. Đáng làm lại khi có người chơi thật.
3. **Ghi sẵn vào cột** — chỉ khi chạm mốc ở §4.
