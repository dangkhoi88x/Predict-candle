# Trang quản trị — kế hoạch

> Nháp 2026-08-31. Nối tiếp N6 trong [SPEC.md](SPEC.md), nhưng rộng hơn: không chỉ chỗ soạn blog
> mà là một chỗ để vận hành cả app mà không phải sửa YAML rồi deploy lại.

## 1. Đang có gì

| | |
|---|---|
| Quyền | **Không có role nào.** `JwtAuthenticationFilter` xác thực ra một `Long` userId, hết. `.authenticated()` nghĩa là "bất kỳ ai cắm ví" — trên game công khai thì là tất cả mọi người. |
| Cửa hẹp duy nhất | `MediaController` tự đối chiếu ví của người gọi với `candles.media.admin-wallets`. Đây là khuôn mẫu duy nhất trong repo cho khái niệm "admin". |
| API đã có | `POST /api/media/images`, `DELETE /api/media/images` (Cloudinary). **Chưa có giao diện nào gọi chúng.** |
| Blog | 3 bài, 21 ảnh, nằm cứng trong `blog.js` dưới dạng mảng `POSTS`. Mỗi bài: `title`, `cover` (SVG chuỗi) hoặc `coverImg` (URL), `tags`, `source`, `sourceUrl`, `content[]` gồm block `text`/`image`, `imageCredit`. |
| Asset | Đọc từ `candles.assets` trong YAML, `CandleSyncScheduler.ensureAssets()` tạo nếu thiếu. Thêm một cặp giao dịch = sửa file + restart. |
| Ops | Không có gì quan sát được từ ngoài: muốn biết sync tới đâu phải đọc log. |

## 2. Quyết định cần chốt trước

### 2.1 Ai là admin — allowlist trong config, hay role trong DB?

**Đề xuất: giữ allowlist config**, nhưng gộp về một chỗ `candles.admin.wallets` thay vì
`candles.media.admin-wallets` như hiện tại.

| | Allowlist config | Role trong DB |
|---|---|---|
| Cấp quyền | Sửa biến môi trường + restart | Cập nhật một dòng, tức thì |
| Rủi ro | Không thể leo thang từ trong app | Ai chiếm được một tài khoản admin là chiếm cả hệ thống; cần đường bootstrap |
| Hợp khi | 1–2 người, đổi vài tháng một lần | Có đội, phân vai, cần audit |

Dự án này đang ở vế trái. **Điều kiện đổi ý:** khi cần phân quyền nhỏ hơn "admin/không admin"
(ví dụ người viết blog không được xoá media), hoặc khi có người thứ ba cần cấp quyền gấp.

### 2.2 Tab thứ 7 hay trang riêng?

**Đề xuất: trang riêng `/admin.html`.**

`index.html` hiện nạp 14 file JS cho *mọi* khách. Trình soạn thảo, upload, bảng biểu là gánh
nặng chết với người chỉ vào chơi đoán nến — đúng thứ mà ghi chú "deferred tabs" trong CLAUDE.md
đang cố tránh. Trang riêng cũng cho phép markup thoáng hơn thay vì ép vào lưới của game.

`auth.js` khôi phục phiên từ refresh cookie nên đăng nhập ví hoạt động y nguyên ở trang mới.

### 2.3 Nguyên tắc bất di bất dịch

Ẩn một tab ở client **không phải** là bảo mật. Mọi endpoint `/api/admin/**` phải tự kiểm tra
phía server. Giao diện chỉ hỏi `GET /api/admin/me` để biết nên vẽ gì, không bao giờ để câu trả
lời đó quyết định quyền.

## 3. Các giai đoạn

### Giai đoạn 0 — nền quyền  ·  S  ·  ✅ XONG

Không thấy được gì nhưng mọi thứ sau đều đứng trên nó.

Đã làm khác kế hoạch ban đầu ở một điểm: **vai trò nằm trong DB** (`users.role`, enum
`USER`/`ADMIN`) chứ không chỉ là allowlist đọc trực tiếp mỗi lần kiểm tra. Nguồn sự thật vẫn là
config — `AdminRoleReconciler` đối chiếu mỗi lần khởi động, thăng ví có trong danh sách và **hạ**
admin không còn trong danh sách — nhưng ứng dụng đọc vai trò từ DB, nên `hasRole` của Spring
Security dùng được và mọi chỗ khác chỉ cần hỏi "có phải admin không".

- `Role` enum, cột `users.role` (migration `V4`), `AdminProperties` + `AdminWallets`
  (có fallback về `candles.media.admin-wallets` kèm cảnh báo).
- Access token mang claim `role`; `JwtAuthenticationFilter` cấp `ROLE_USER`/`ROLE_ADMIN`.
- `SecurityConfig`: `/api/admin/**` và `/api/media/**` yêu cầu `hasRole("ADMIN")`, kèm
  entry point + access denied handler trả JSON đúng khuôn `{message}`.
- `AdminAccess.requireAdmin()` đọc lại vai trò từ DB cho mọi thao tác ghi — bịt khe 15 phút
  của token cũ. `MediaController` bỏ hẳn phần tự kiểm tra ví.
- `User.assignRole()` bump `tokenVersion`, nên đổi vai trò là chấm dứt phiên hiện có.
- `GET /api/admin/me`, `admin.html` + `admin.js` với ba trạng thái, link ⚙ Quản trị chỉ hiện với admin.
- `AdminAuthorizationTest` — 6 test cho đúng các luật này.

### Giai đoạn 1 — thư viện media  ·  M  ·  ✅ XONG

Thắng nhanh nhất: API đã xong, chỉ thiếu giao diện. Và trình soạn blog sẽ cần nó.

- `GET /api/media/images?folder=&cursor=&limit=` — phân trang bằng cursor của Cloudinary.
  Trả ba URL cho mỗi ảnh: gốc, `w_320` cho lưới, `w_1120` để dán vào bài. Bắt lưới vẽ ảnh gốc
  1.8 MB rộng 160px là cách nhanh nhất làm trang admin nặng.
- `admin-media.js`: kéo thả upload nhiều file, lưới ảnh, copy URL, xoá có xác nhận nêu tên file.
- **Bộ chọn ảnh cho trình soạn blog** — cùng danh sách, cùng code, chỉ khác là bấm "Chọn" thì
  đóng lại và trả ảnh về. Chọn xong tự điền cả `w`/`h` vào khối ảnh, nên kích thước không còn
  phải gõ tay (gõ sai là layout nhảy khi ảnh tải xong).
- Đây là mắt nối mà giai đoạn 2 còn thiếu: trình soạn không còn bắt dán URL.

### Giai đoạn 2 — CMS cho blog  ·  L  ·  ✅ XONG

Phần lớn nhất, và là lý do chính có trang admin.

Đánh số migration thực tế là **V5** (bảng) và **V6** (seed), vì V4 đã dùng cho `users.role`.

- **Migration V5**: bảng `blog_posts` — `id`, `slug`, `title`, `tags text[]`, `source`,
  `source_url`, `image_credit`, `cover_svg`, `cover_img`, `body jsonb`, `published boolean`,
  `position int`, `created_at`, `updated_at`.
  Dùng `jsonb` cho `body` để giữ nguyên cấu trúc block `text`/`image` sẵn có — chuẩn hoá thành
  bảng con là bổ củi bằng máy xúc cho 3 bài viết.
- **Migration V6**: nạp sẵn 3 bài hiện tại, sinh bằng `scratchpad/extract-posts.js` đọc thẳng
  `POSTS` từ `blog.js` — không chép tay 21 ảnh. SQL dùng dollar-quoting nên SVG và dấu nháy
  trong nội dung không cần escape.
- `GET /api/blog/posts` công khai; `POST/PUT/DELETE /api/admin/blog/posts` sau `AdminGuard`.
- `blog.js` đổi sang gọi API. Mảng `POSTS` từng được giữ làm dự phòng cho tới khi đường API
  chạy thật ổn; **đã gỡ** sau khi API chạy qua deploy thật. Chỗ của nó giờ là một thông báo
  lỗi thật (`.view-notice`) — nội dung cũ giả làm nội dung mới còn tệ hơn một tab trống có
  giải thích.
- Trình soạn (`admin-blog.js`): form + **Tiptap**. `body` giờ là **tài liệu ProseMirror**
  (`{"type":"doc","content":[…]}`), V12 chuyển 3 bài seed từ mảng block cũ sang. Vẫn đúng cột
  `jsonb` đó, không thêm cột.

  Bản trước lần lượt là trình dựng block, rồi một khung `contenteditable` tự viết. Cả hai đều
  vướng cùng một trần: block chỉ giữ **chuỗi thuần**, nên không có tiêu đề, danh sách, trích
  dẫn, hay in đậm giữa câu. Kéo dài định dạng đó thêm nữa là tự viết một Tiptap tệ hơn.

  **Trang blog công khai KHÔNG nạp Tiptap.** `blog-render.js` (7 KB) vẽ cùng tài liệu đó bằng
  `createElement`. Nạp extension của Tiptap ở đó nghĩa là mỗi người đọc tải ~395 KB trình soạn
  để xem ba bài — trên đúng trang mà cả một release vừa dồn sức giảm cân nặng (4.374 KB → 335 KB).
  Đo thực tế: JS trang công khai 208 KB → **215 KB**, còn 395 KB nằm lại ở `admin.html`.

  Giá phải trả là một ràng buộc cần nói rõ: **mọi node/mark mà trình soạn sinh ra đều phải có
  nhánh trong `blog-render.js`.** Thêm extension mà quên thì trang công khai không vẽ được thứ
  admin vừa đăng. Node lạ rơi về text của nó kèm `console.warn`, chứ không biến mất im lặng.

  Hai điểm cố ý khác: `Image` là node tự mở rộng có `width`/`height` (Tiptap gốc bỏ), vì trang
  công khai giữ chỗ cho ảnh bằng hai số đó — nên `POST /api/media/images` cũng trả về kích
  thước, để ảnh **dán vào** được đối xử như ảnh chọn từ thư viện. Và `href` bị kiểm ở **cả hai
  đầu**: Tiptap chỉ nhận http/https, `blog-render.js` kiểm lại lúc vẽ — trình soạn là tiện lợi,
  không phải ranh giới an toàn.
- Chọn ảnh từ thư viện: đã xong ở giai đoạn 1.

### Giai đoạn 2b — ba thư viện nội dung  ·  M  ·  ✅ XONG

41 mục còn lại trong JavaScript: 13 mẫu nến, 16 mẫu hình kỹ thuật, 12 ghi chú tâm lý. Cùng cơ
chế với blog — bảng `content_items` (V7) + seed sinh tự động từ chính ba file JS (V8), `body`
là `jsonb` giữ nguyên object mà renderer vốn đã đọc, nên ba renderer chỉ đổi một dòng.

**Ranh giới quan trọng nhất của cả trang admin nằm ở đây.** Khoá (`item_key`) của mẫu nến và
mẫu hình không phải nhãn — nó là liên kết tới matcher trong `PatternLibrary` /
`TechnicalPatternLibrary`, vốn là Java. Nên `ContentKind.boundToCode()` quyết định:

| | Sửa chữ | Đổi khoá | Thêm | Xoá |
|---|---|---|---|---|
| Mẫu nến, mẫu hình | ✅ | ❌ | ❌ | ❌ |
| Ghi chú tâm lý | ✅ | ✅ | ✅ | ✅ |

Cấm ở tầng service chứ không phải controller, nên luật đúng dù gọi vào bằng đường nào. Giao
diện cũng không vẽ nút mà server sẽ từ chối. `ContentKeysMatchMatchersTest` khoá bất biến hai
chiều: mọi thẻ đều có matcher, và mọi matcher đều có thẻ.

`content.js` là global dùng chung cho cả ba tab. `load(kind)` giờ **ném lỗi** thay vì trả về
mảng dự phòng, và `notice(el, text)` là chỗ duy nhất bốn tab nói "không tải được" — nói cùng
một kiểu.

### Giai đoạn 3 — bảng vận hành  ·  M  ·  ✅ XONG

Trả lời được "app đang ổn không" mà không phải SSH đọc log.

- `GET /api/admin/ops` trả một snapshot: sức khoẻ từng asset, schema, cấu hình, hoạt động.
- **Độ trễ mới là con số quan trọng, không phải số nến.** Một feed đã chết trông y hệt một feed
  khoẻ nếu chỉ nhìn số nến — nó lớn trong cả hai trường hợp. Cờ `stale` tính từ tuổi của nến
  mới nhất, ngưỡng là 2 chu kỳ khung thời gian cộng 10 phút (job chạy vào phút thứ 5).
- `POST /api/admin/ops/sync/{symbol}` chạy đúng delta fetch mà cron hằng giờ chạy.
- Schema: phiên bản hiện tại, số migration đã chạy, **số đang chờ**. Dùng `info().applied()` và
  `info().pending()` của Flyway chứ không tự đếm hàng có `installedOn` — database đã baseline
  giữ các migration dưới mốc baseline mà không có ngày cài, và đếm tay sẽ báo một schema khoẻ
  là đang chờ chạy. Test bắt được đúng lỗi này.
- Cấu hình `candles.round.*` chỉ đọc: trang admin sửa được cấu hình là tạo nguồn sự thật thứ hai
  bên cạnh `application.yaml`.
- Hoạt động: lượt đoán hôm nay và 7 ngày, tỉ lệ đúng toàn hệ thống, số tài khoản, số nội dung.

### Giai đoạn 4 — asset và người chơi  ·  M  ·  ✅ XONG

**Asset.** Cột `assets.enabled` (V9). Config vẫn seed như cũ nên deployment đang chạy không đổi
gì; thứ chuyển vào DB là *cặp nào được mời chơi*. `CandleSyncScheduler` giờ lặp theo asset đang
bật trong DB thay vì theo YAML, và `RoundSelectionService.resolveAsset` từ chối cặp đang tắt —
picker chỉ là một danh sách trong trình duyệt, còn endpoint round nhận bất cứ mã nào được gửi.

Trình tự bắt buộc: **thêm → backfill → bật**. Cặp mới tạo ra ở trạng thái tắt vì chưa có nến,
mà một cặp không nến nằm trong picker là một yêu cầu round không thể trả lời. Bật khi chưa có
nến bị từ chối kèm lý do.

**Picker của game phải động theo.** Nếu không, thêm asset từ admin chỉ tạo ra một hàng DB không
ai chơi được. `GET /api/assets` công khai, `app.js` dựng pill từ đó, và 4 nút cứng trong
`index.html` ở lại làm dự phòng. Cờ `compact` (hiện `$78.2K` hay `$2,456`) không còn là bảng
tra cứng mà suy từ giá nến cuối — bảng cũ nói đúng y hệt cho 4 cặp cũ, còn cách này đúng cả với
cặp thêm sau.

**Người chơi.** Danh sách ví, tên, vai trò, số lượt, tỉ lệ đúng, lần chơi cuối. Đổi tên và xoá
được; **không** có đường cấp vai trò (config sở hữu) và không sửa được điểm — admin chỉnh được
tổng điểm thì mọi bảng xếp hạng thành vô nghĩa. Xoá tài khoản admin bị chặn: gỡ ví khỏi
`candles.admin.wallets` rồi khởi động lại trước đã.

### Giai đoạn 5 — vỏ dashboard và pane Tổng quan  ·  M  ·  ✅ XONG

Bốn giai đoạn trước xếp 7 section xổ dọc một trang. Muốn tới bảng người chơi phải cuộn qua cả
thư viện ảnh, và không có câu trả lời nào ở màn hình đầu tiên. Giai đoạn này thay bố cục:
sidebar ba nhóm, topbar có breadcrumb, và **một pane tại một thời điểm**.

**Đổi pane bằng thuộc tính, không bằng `.hidden`.** Sáu module admin đã sở hữu `.hidden` trên
section của chúng và đặt lại nó mỗi lần nhận `candles:admin`. Nếu nav cũng viết vào `.hidden`
thì bấm Sync trong Vận hành xong module refresh là pane tự nhảy về. Nên `admin-nav.js` chỉ đặt
`data-pane` trên container, còn CSS lấy giao của hai điều kiện: container đang chọn pane đó
**và** module chưa ẩn nó. Hai cơ chế không chạm nhau.

**`GET /api/admin/stats?range=week|month|year`.** `/api/admin/ops` chỉ có số hiện tại — đủ cho
bốn thẻ KPI, không đủ cho một biểu đồ. Endpoint mới nhóm lịch sử guess theo `date_trunc`
**trong UTC** (cùng mốc "hôm nay" mà OpsService đang dùng), trả ba chuỗi: chuỗi theo range cho
biểu đồ chính, 14 ngày cho bảng người chơi hoạt động, 12 tuần cho đường tỉ lệ đúng. Bucket rỗng
vẫn có mặt và bằng 0 — bỏ chúng đi là vẽ hai mốc cách nhau như thể chúng liền nhau. Guess hết
giờ không có hướng nên không tính vào LONG lẫn SHORT.

Cache 60 giây trong service, và index `(created_at)` ở V11: index cũ là `(user_id, created_at)`
và không giúp được một truy vấn không có `user_id`. Đây là trang người ta để mở rồi bấm làm
mới — nên nút "Làm mới" gửi `&fresh=true` để bỏ qua cache, nếu không nó chỉ làm mới nửa trang
(mốc thời gian và 4 thẻ KPI đổi, ba biểu đồ thì không).

**Hai con số "lượt đoán", và chọn nhầm là sai nhìn thấy được.** Guess hết giờ không có hướng,
nên mỗi bucket mang cả hai: `guesses` (mọi hàng — đúng mẫu số mà `PlayerScore` và bảng vận hành
đang dùng) và `answered` (long + short, chiều cao cột, vì chú giải chỉ ghi SHORT và LONG). Mọi
tỉ lệ đúng trên trang này là `correct / guesses`; lấy `correct / answered` cao hơn khoảng 9 điểm
phần trăm trên dữ liệu hiện tại — đủ để con số lớn và đường kẻ ngay dưới nó nói hai điều khác
nhau. Nhãn cạnh biểu đồ vì thế là "Đã trả lời", không phải "Tổng lượt".

Ba thẻ KPI đầu lấy số từ snapshot ops; thẻ **Tài khoản người chơi** là số tài khoản cộng dồn,
nên sparkline của nó là đường cộng dồn đó (`accounts[]`, dựng từ `users.signupsByDay` đi ngược
từ `count()`) và delta là mức tăng của chính nó trong 7 ngày — không phải người chơi hoạt động
theo tuần như bản đầu, vốn đặt ba đại lượng khác nhau lên cùng một thẻ.

**Pane Tổng quan không nhân bản bảng `#ops-assets`.** Một id, một chỗ. Thay vào đó là một dải
cảnh báo chỉ hiện khi có asset trễ, cộng badge đếm trên mục nav Vận hành — đủ để biết có
chuyện, còn chi tiết ở đúng pane của nó. KPI lấy thẳng từ snapshot mà `admin-ops.js` đã fetch
(nó phát `candles:ops`), không gọi lại lần hai: hai pane hỏi cùng một câu hai lần chỉ tạo cơ
hội trả lời khác nhau.

**Token riêng.** `--adm-*` trong `style.css`, và cả layer admin nằm dưới `.admin-shell`. Trang
game và trang quản trị dùng chung `.ghost-btn`, `.pill`, `.status`; scope là thứ giữ cho hai
trang không phải thoả hiệp về hình thức của nhau.

## 4. Rủi ro

| Rủi ro | Xử lý |
|---|---|
| Blog là nội dung công khai; migration hỏng là trắng tab | ~~Giữ mảng `POSTS` dự phòng~~ — đã qua deploy thật và đã gỡ. Lỗi giờ hiện thông báo, và tab blog đặt lại cờ `built` để mở lại tab là thử lần nữa |
| Xoá Cloudinary không hoàn tác | Hộp thoại xác nhận có tên ảnh; không xoá hàng loạt ở bản đầu |
| Đây là nhóm endpoint ghi đầu tiên ngoài media | Cho `/api/admin/**` vào `RateLimiter` luôn |
| CSRF | Access token nằm trong bộ nhớ và gửi qua header `Authorization`, không phải cookie, nên CSRF không khai thác được. Đừng chuyển sang cookie auth cho trang admin chỉ vì tiện. |
| Ảnh upload không ai dùng tồn đọng | Giai đoạn 1 chỉ liệt kê; dọn rác để sau, cần biết ảnh nào đang được bài nào tham chiếu |

## 5. Thứ tự

Cả sáu giai đoạn (0, 1, 2, 2b, 3, 4, 5) đã xong. Trang admin hiện có: pane Tổng quan, bảng vận
hành, CRUD blog, ba thư viện nội dung, thư viện ảnh, quản lý cặp giao dịch và người chơi — tất
cả trong một vỏ dashboard có sidebar.

**Ô tìm kiếm trong topbar** (`admin-search.js`) đọc **DOM đã render**, không đọc dữ liệu của
các module. Nghe vòng vo nhưng đúng với vỏ này: cả bảy pane đều đã dựng và nằm sẵn trong tài
liệu cùng lúc — chỉ có CSS giấu sáu cái còn lại — nên mọi hàng đều ở đó, quét gần như miễn phí
và không module nào phải mở dữ liệu của mình ra.

Đổi lại, chỉ số là **đúng phần đã tải**: thư viện nội dung chỉ giữ loại đang chọn, thư viện ảnh
chỉ giữ các trang đã fetch. Trạng thái rỗng nói thẳng điều đó thay vì để người đọc hiểu là
không có.

So khớp bỏ dấu (`van hanh` ra `Vận hành`) và gộp `- _ / .` thành khoảng trắng (`cau truc` ra
`cau-truc-thi-truong`) — nửa số chữ tìm được ở đây là slug và id Cloudinary. Cũng quét cả
thuộc tính `title`, vì đó là chỗ giữ địa chỉ ví đầy đủ và publicId đầy đủ trong khi ô chỉ hiện
bản rút gọn. Chọn một kết quả thì `CandleAdminNav.go` đổi pane, cuộn tới hàng và tô nền
`--adm-warn` trong 1,6 giây rồi tự tắt.

Việc còn nợ: cho `/api/admin/**` vào `RateLimiter` (§4 vẫn ghi là sẽ làm, hiện chưa route admin
nào dùng); và trang settings cho `candles.round.*` nếu việc cân bằng độ khó bắt đầu cần đổi số
thường xuyên.
