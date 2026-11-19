# Flowme — biến Galaxy Z Fold 8 thành “mặt dây Omi”

> Flowme là tên mới của NgheNhớ từ bản 2.0.0. Mã gói (`vn.nghenho`) và khoá ký được giữ nguyên, nên cài đè lên NgheNhớ vẫn giữ toàn bộ dữ liệu, key và cài đặt.

**Tải APK:** [Releases → bản mới nhất](https://github.com/huuthanhueh-debug/Flowme/releases/latest) (Android 11 trở lên, tối ưu cho Galaxy Z Fold).

Bạn chỉ cần mang điện thoại. Bấm **BẬT** một lần buổi sáng, Flowme sẽ nghe suốt ngày. Mỗi khi có tiếng nói, app ghi lại, chép lời có dấu, phân biệt người nói, tóm tắt, rút ra việc cần làm, lịch hẹn và sơ đồ tư duy. Muốn dừng thì bấm **TẮT**.

---

## 1. Vì sao tự viết app (nghiên cứu nhanh)

| Lựa chọn | Ưu | Nhược với nhu cầu của bạn |
|---|---|---|
| **Omi app – chế độ chỉ dùng điện thoại** | Có sẵn, cùng hệ sinh thái Omi | Âm thanh đi qua máy chủ Omi; gói miễn phí giới hạn số phút; hướng dẫn chính thức yêu cầu để app mở và không nói rõ ghi nền cả ngày khi tắt màn hình có bền không |
| **Samsung Ghi âm + Galaxy AI** (có sẵn trên Fold 8) | Chép lời, tách người nói, tóm tắt ngay trên máy | Phải tự bấm ghi cho từng cuộc; không tự cắt thành từng cuộc hội thoại; không có việc cần làm, lịch hẹn, sơ đồ tư duy, tìm kiếm hay hỏi đáp xuyên ngày |
| Otter / Plaud / Limitless | Mạnh cho cuộc họp | Trả phí theo tháng, tiếng Việt hạn chế, dữ liệu nằm trên cloud của họ |
| **Flowme (app này)** | Nghe cả ngày, tự cắt cuộc hội thoại, dùng API key của bạn, dữ liệu nằm trên máy | Cần một lần tạo API key; mất phí API nếu vượt gói miễn phí |

## 2. Đối chiếu với tài liệu Omi

| Chức năng Omi | Flowme trên Z Fold 8 |
|---|---|
| Ghi âm liên tục, lọc tạp âm | Dịch vụ nền loại micro chạy cả ngày. Tự nhận biết tiếng nói và bỏ qua khoảng im lặng (đỡ tốn pin, dung lượng và tiền API). Dùng bộ lọc ồn và cân bằng âm lượng của máy, có 4 nguồn micro để chọn |
| Chép lời thời gian thực, hơn 25 ngôn ngữ, tiếng Việt | Chép lời gần thời gian thực: trễ khoảng 20 giây, 1 phút hoặc 3 phút tuỳ chọn. Tiếng Việt có dấu, nhận diện cả tiếng Anh xen lẫn. Nhà cung cấp: Gemini, Groq Whisper, Deepgram, OpenAI, hoặc máy chủ Whisper riêng |
| Phân biệt người nói | “Bạn”, “Người A”, “Người B”… Gemini, Deepgram và OpenAI-diarize tách người nói ngay khi chép lời. Từ bản 1.2.0, khi dùng Groq/Whisper (không tách được) thì AI tóm tắt sẽ gán người nói cho từng câu dựa vào nội dung. Chạm vào tên để đổi thành tên thật |
| Tóm tắt AI (ChatGPT, Claude) | Mỗi cuộc hội thoại có tiêu đề, emoji, danh mục, tóm tắt và ý chính. Chọn Gemini, Claude (Opus/Sonnet/Haiku 5.5), OpenAI, Groq hoặc máy chủ riêng |
| Trích xuất việc cần làm | Danh sách việc có người phụ trách và hạn chót, đánh dấu xong được. Tab **Việc** gom việc của mọi ngày |
| Sơ đồ tư duy | Vẽ sơ đồ ngay trong app (kéo, chụm để phóng to), sao chép dàn ý để dán vào XMind, Markmap, Notion |
| Google Calendar, Notion, Gmail | **Lịch:** câu “hẹn 3 giờ chiều mai” thành một gợi ý có nút *Thêm vào lịch*, hoặc tự thêm nếu bạn bật. App ghi qua lịch Google có sẵn trên máy, không cần đăng nhập thêm. **Notion:** tự tạo trang trong database. **Gmail:** soạn sẵn email tóm tắt. **Webhook** cho Zapier, Make, n8n |
| Lưu ngoại tuyến và tự đồng bộ | Âm thanh luôn lưu vào máy trước. Mất mạng thì nằm chờ, có mạng lại thì tự xử lý (có tuỳ chọn chỉ dùng Wi-Fi) |
| Mã nguồn mở, tự host, API riêng | Toàn bộ mã nguồn ở thư mục này. Không có máy chủ trung gian. Key được mã hoá bằng Android Keystore. Chạy được với Whisper, Ollama tự host |
| *(Thêm, có trong app Omi)* “Hỏi Omi” | Tab **Hỏi AI**, ví dụ “Hôm nay tôi hứa làm gì?” hoặc “Tuần này anh Minh nói gì về ngân sách?” |
| Pin 10–14 giờ | Dùng pin điện thoại. Chỉ mã hoá và gửi đi khi có tiếng nói. Cần cho app chạy nền không giới hạn (mục 4) |

## 3. Cài lên Z Fold 8

1. Chép file `dist/Flowme-2.0.0.apk` sang điện thoại bằng một trong các cách: Quick Share, cáp USB, Google Drive, hoặc gửi Zalo/Telegram cho chính mình. Nếu máy đã cài NgheNhớ (1.0–1.2), cứ cài đè lên: dữ liệu, API key và cài đặt đều được giữ nguyên.
2. Trên điện thoại, mở file APK. Khi máy hỏi, bật **Cho phép cài từ nguồn này**, sau đó chọn **Cài đặt**. Nếu Play Protect cảnh báo “ứng dụng không rõ”, chọn **Vẫn cài đặt**. Cảnh báo này xuất hiện vì app tự build, chưa qua Play Store.
3. Mở **Flowme** và cho phép **Micro** cùng **Thông báo**.

## 4. Thiết lập lần đầu (khoảng 5 phút)

**a) Chọn cấu hình AI** trong **Cài đặt → Cài đặt nhanh**:

| Cấu hình | Cần key | Tách người nói | Chi phí (tham khảo, hãy xem bảng giá hiện hành) |
|---|---|---|---|
| ⭐ **Gemini – 1 key cho tất cả** (mặc định) | aistudio.google.com → *Get API key* | Có | Có gói miễn phí giới hạn lượt/ngày. Ghi cả ngày có thể vượt, khi đó cần bật thanh toán (khoảng vài nghìn đồng mỗi giờ nói) |
| 🆓 **Groq** | console.groq.com | Không | Miễn phí vài giờ âm thanh mỗi ngày |
| 🎯 **Claude + Groq chép lời** | platform.claude.com + console.groq.com | Không | Phần chép lời miễn phí. Claude tính phí theo lượt, tách biệt gói Claude Pro: Opus 5.5 tóm tắt tốt nhất, Haiku 5.5 rẻ nhất |
| 🗣️ **Claude + Gemini** | Claude + aistudio.google.com | Có | Gemini chép lời có gói miễn phí |
| 🐳 **DeepSeek + Groq chép lời** | platform.deepseek.com + Groq | Không | DeepSeek rẻ, hiểu tiếng Việt và English. Có thể gõ tên model khác, vd. bản "pro" |

> **Lưu ý:** Claude và DeepSeek chỉ làm **tóm tắt và hỏi đáp**, không chép lời giọng nói được. Vì vậy luôn cần thêm 1 key chép lời: Groq (miễn phí) hoặc Gemini (miễn phí, có tách người nói).
>
> **Ngôn ngữ:** mặc định là **Tiếng Việt + English**. Câu nào nói tiếng Anh sẽ được giữ nguyên tiếng Anh, không bị dịch. Bản tóm tắt viết bằng tiếng Việt (đổi được sang English hoặc theo ngôn ngữ cuộc trò chuyện). Hỏi AI trả lời theo ngôn ngữ bạn hỏi.

Dán key vào ô tương ứng rồi bấm **Kiểm tra kết nối AI**.

**b) Cho chạy nền cả ngày (bắt buộc trên Samsung):**
- Trong app, bấm **Cho phép chạy nền** ở thẻ cảnh báo trên màn hình chính.
- Vào **Cài đặt → Pin → Giới hạn sử dụng nền → Ứng dụng không bao giờ ngủ**, thêm **Flowme**.
- Vào **Thông tin ứng dụng Flowme → Pin**, chọn **Không hạn chế**.

**c) (Tuỳ chọn)** Nhập tên của bạn, cho phép truy cập Lịch, điền token Notion, webhook và email nhận tóm tắt.

## 5. Dùng hằng ngày

- **Bật/Tắt** có 4 cách:
  - Nút tròn lớn trong app.
  - Ô **Flowme** trong Cài đặt nhanh: vuốt xuống 2 lần, bấm ✏️, kéo ô vào.
  - **Widget** trên màn hình chính hoặc màn hình ngoài của Fold.
  - Nút **Tắt** trên thông báo.
- Thông báo cố định có thêm nút **Tạm dừng** (micro tắt hẳn) và **Chốt đoạn** (kết thúc cuộc trò chuyện hiện tại để tóm tắt ngay).
- Một **cuộc hội thoại** tự kết thúc khi mọi người im lặng quá 3 phút (đổi được 1–15 phút). Sau đó vài giây đến một phút bạn sẽ nhận thông báo tóm tắt.
- **Trí nhớ** liệt kê theo ngày, có tìm kiếm trong cả bản ghi và lọc theo danh mục. Khi mở Fold ra, màn hình tự chia 2 cột: danh sách bên trái, chi tiết bên phải.
- Mỗi cuộc hội thoại có 4 tab: **Tóm tắt · Việc** (việc cần làm + lịch hẹn) **· Sơ đồ · Bản ghi** (nghe lại từng đoạn, đổi tên người nói). Menu ⋮ có: chia sẻ, gửi Gmail, gửi Notion, webhook, tóm tắt lại, chép lời lại, xoá.
- **Xoá hội thoại cũ** (bản 1.2.0):
  - Trong **Trí nhớ**, bấm **Chọn** (hoặc nhấn giữ một cuộc) để chọn nhiều cuộc, chọn cả ngày, hoặc **Chọn hết**, rồi bấm 🗑.
  - Nút **🧹 Dọn** xoá nhanh mọi cuộc trước hôm nay, cũ hơn 7 ngày hoặc cũ hơn 30 ngày. Hộp thoại hiện trước số cuộc sẽ bị xoá.
  - Khi xoá, **việc chưa xong vẫn được giữ** trong tab Việc. Lịch đã thêm vào Lịch Google không bị ảnh hưởng.
- **Hỏi dọn mỗi sáng** (bản 1.2.0): sang ngày mới, nếu các cuộc hôm trước đã lưu lịch hẹn xong, màn hình chính hiện thẻ **🧹 Đã sang ngày mới – dọn trí nhớ?** với 3 nút **Xoá N cuộc / Giữ lại / Để sau**. Khoảng 8:00 sáng còn có thông báo với cùng các nút đó.
  - Cuộc nào còn lịch hẹn **chưa thêm vào Lịch** thì được giữ lại, không bị đưa vào danh sách xoá.
  - Tắt tính năng này trong **Cài đặt → 4 · Lưu trữ**.

## 6. Những điều cần biết

- **Android không cho app tự bật micro từ nền.** Sau khi máy khởi động lại, hoặc khi hệ thống dừng app, Flowme hiện thông báo có nút **Bật lại** để bạn bấm 1 chạm.
- **Cuộc gọi điện thoại không ghi được.** Android tắt tiếng mọi app ghi âm khi đang gọi, và màn hình chính sẽ báo “Micro đang bị cuộc gọi/ứng dụng khác dùng”. Muốn ghi cuộc gọi thì bật loa ngoài.
- **Độ chính xác tách người nói** chỉ đảm bảo trong từng đoạn ngắn. Giữa các đoạn của cùng một cuộc dài, “Người A” có thể bị đổi nhãn. Bạn sửa bằng cách chạm vào tên.
- **Dữ liệu:** nằm trong bộ nhớ riêng của app. Âm thanh được giữ 7 ngày rồi tự xoá (đổi trong Cài đặt). Bản ghi và tóm tắt giữ mãi, có nút **Xuất toàn bộ** ra `Download/Flowme`. Gỡ app là mất dữ liệu, nên hãy xuất ra trước khi gỡ.
- **Pháp luật & lịch sự:** hãy cho người đối diện biết khi bạn ghi âm cuộc trò chuyện.
- **Bản ghi không hiện?** Từ bản 1.2.0, màn hình chính nói đúng lý do thay vì chỉ báo "cần có mạng":
  - Điện thoại chưa có mạng.
  - Đang chờ Wi-Fi vì bạn bật "Chỉ gửi khi có Wi-Fi".
  - Lỗi của nhà cung cấp AI (vd. hết hạn mức 429), có kèm gợi ý cách xử lý.

  Cách xử lý:
  - Bấm **Gửi ngay** để thử lại tức thì.
  - Vào **Cài đặt → Kiểm tra chép lời**: app gửi đoạn ghi gần nhất và báo kết quả thật.
  - Xem **Cài đặt → 8 · Nhật ký xử lý** (✓ thành công, ✗ lỗi). Có nút sao chép để gửi khi cần hỗ trợ.
- **Claude và DeepSeek không chép lời được** (không nhận âm thanh). Hãy dùng thêm key **Gemini** (chép lời có tách người nói) hoặc **Groq** (miễn phí) cho mục *1 · Chép lời*, còn Claude/DeepSeek lo phần tóm tắt.

## 7. Đã kiểm thử những gì

**Bản 2.0.0 – Flowme (06/11/2026):** đổi tên, logo mới, giao diện chuẩn quốc tế, lưu dữ liệu chắc chắn khi thoát app.
- **Logo:** nét sóng âm nhỏ dần rồi cuộn thành vòng (“flow” → “me”) trên nền gradient chàm–tím–xanh ngọc. Có đủ adaptive icon, icon theo chủ đề (Android 13+), icon thanh trạng thái và widget. File gốc ở `design/flowme-logo.svg`.
- **Giao diện:**
  - Bảng màu thương hiệu riêng cho cả chế độ sáng và tối, thay cho màu động của hệ thống. Đã kiểm tra độ tương phản đạt chuẩn WCAG AA bằng unit test.
  - Kiểu chữ và bo góc thống nhất; thẻ thông báo dùng icon và màu thay cho emoji.
  - Nút ghi dùng gradient, kèm viên trạng thái và thẻ thống kê có icon.
  - Bong bóng chat mới; thanh hệ thống tự đổi màu theo chế độ sáng/tối.
- **Lưu khi thoát app:**
  - Câu hỏi đang gõ ở Hỏi AI được ghi ngay vào máy.
  - Nếu app bị đóng đúng lúc AI đang trả lời, app hiện nút **Hỏi lại**.
  - Ô “Thêm việc…” không mất khi chuyển tab.
  - Âm thanh, hội thoại và cài đặt vốn đã lưu bền vào DB, kể cả file đang ghi dở khi app bị tắt đột ngột.
- **Test:** 31 unit test và 12 test trên máy ảo, tất cả đều đạt. Phần mới: độ tương phản màu, kiểu thẻ, câu hỏi chưa trả lời, tên/icon/mã gói, bản nháp ghi xuống đĩa, hỏi lại sau khi bị gián đoạn.
- **Trên máy ảo:**
  - Gõ câu hỏi, thoát app, ép dừng rồi mở lại: câu hỏi và các cuộc hội thoại vẫn còn.
  - Cài đè lên NgheNhớ 1.2.0 bản release: cài đặt được giữ, tên đổi thành Flowme.
  - Chế độ tối đúng màu, kể cả khi đổi lúc app đang mở.


**Bản 1.2.0 (05/11/2026):** sửa lỗi "cứ báo cần có mạng" trên máy thật, sửa phần tách người nói, thêm xoá và dọn hội thoại cũ.
- **Nguyên nhân kẹt:**
  - Việc gửi đi chép lời trước đây chỉ do WorkManager đảm nhận, mà WorkManager đòi mạng "đã xác thực". Một số mạng/máy làm nó tưởng mất mạng dù 5G/Wi-Fi vẫn dùng được.
  - Lỗi của nhà cung cấp (429, 503…) bị giấu sau dòng "cần có mạng".
- **Cách sửa:**
  - App giờ tự gửi ngay khi có đoạn mới, khi vừa có mạng và mỗi phút một lần trong lúc đang nghe. WorkManager chỉ còn là dự phòng và không còn điều kiện mạng.
  - Lượt dự phòng cũ của bản 1.1.0 (nếu còn kẹt) được tự thay thế.
  - Tôn trọng thời gian chờ mà nhà cung cấp yêu cầu, gồm cả "try again in 1m12s" của Groq. Nút **Gửi ngay** bỏ qua thời gian chờ này.
- **28 unit test** (thêm: đọc thời gian chờ 429, nhãn người nói, bản chép lời đánh số), tất cả đều đạt.
- **9 test trên máy ảo**, tất cả đều đạt. Ba test mới:
  - Xử lý trực tiếp, gặp 429 rồi **Gửi ngay**, AI gán đúng "Bạn"/"Người A".
  - Dọn dẹp buổi sáng: giữ cuộc còn lịch chưa thêm, giữ việc chưa xong, chỉ hỏi 1 lần/ngày.
  - Thông báo 8:00 có nút **Xoá / Giữ lại**, bấm "Giữ lại" ngay trên thông báo hoạt động.
- **Trên máy ảo:**
  - Cài đè 1.2.0 lên 1.1.0 thành công.
  - Banner báo đúng lý do (lỗi 429 kèm gợi ý; "điện thoại chưa có mạng"). Bật mạng lại thì tự chép lời mà không cần bấm.
  - Chọn nhiều cuộc rồi xoá; hộp thoại 🧹 Dọn đếm đúng số cuộc.
  - Thẻ "dọn trí nhớ" trên màn hình chính.
  - Kiểm tra chép lời; nhật ký xử lý.

**Bản 1.1.0:**
- Sửa 9 lỗi do agent review độc lập phát hiện và 7 lỗi tự tìm ra. Đáng kể nhất:
  - Xoá cuộc hội thoại đang diễn ra làm các đoạn sau bị mất.
  - Key sai làm cả hàng chờ bị đánh lỗi; nay app dừng lại và báo "⚠️ kiểm tra API key".
  - "Tóm tắt lại" thêm trùng lịch hẹn.
  - "Thử lại" bị kẹt sau thời gian chờ.
  - Hỏi AI mất câu trả lời khi chuyển tab.
  - Màn hình chính tốn CPU (giảm từ khoảng 70% xuống 8% khi đang nghe).
  - Nút "Bật lại" có thể làm app crash.
- **Thử trọn vòng ghi âm bằng giọng nói tổng hợp, trên máy ảo:**
  - Nhận ra tiếng nói và cắt đoạn đúng.
  - Gom đúng vào cuộc hội thoại.
  - Tự khép sau 1 phút im lặng, rồi tóm tắt và thông báo.
  - Tắt màn hình hoặc vào **Doze ngủ sâu** vẫn ghi và vẫn chép lời.
  - **Mất mạng** thì nằm chờ, có mạng lại thì tự xử lý.
  - **Key bị từ chối (401)** thì không mất đoạn nào.
  - Tạm dừng rồi Chốt đoạn hoạt động đúng.
  - **Monkey test 2.500 thao tác ngẫu nhiên: 0 crash, 0 ANR.**

**Bản 1.0.0 (05/11/2026):**

- **25 unit test** (JVM), tất cả đều đạt. Phạm vi: phát hiện tiếng nói và cắt đoạn bằng tín hiệu giả lập, đọc phản hồi Gemini/OpenAI/Deepgram/Claude, lọc câu "ảo giác" của Whisper, ngày giờ, sơ đồ tư duy, header AAC.
- **3 test trên máy ảo Android 15**, tất cả đều đạt. Phạm vi:
  - Mã hoá AAC 16 kHz, chuyển sang .m4a, phát lại.
  - Mã hoá API key bằng Keystore.
  - **Trọn luồng** chạy với một máy chủ AI giả lập chuẩn OpenAI: âm thanh → tải lên → chép lời → tóm tắt → việc cần làm, lịch hẹn, sơ đồ → hỏi trí nhớ.
- **Trên máy ảo:**
  - Nút BẬT/TẮT, Tạm dừng (micro được nhả thật) / Tiếp tục.
  - Ô Cài đặt nhanh.
  - Thông báo có 3 nút.
  - Thêm lịch hẹn vào Lịch (kèm nhắc trước 30 phút).
  - Giao diện 2 cột khi mở Fold.
  - App crash: hệ thống tự bật lại dịch vụ và micro.
  - Khởi động lại máy: hiện thông báo “Bật lại”, bấm vào thì nghe tiếp ngay.
- **Chưa kiểm chứng:**
  - Chạy trên Z Fold 8 thật (cơ chế "cho ngủ" của Samsung, pin cả ngày, widget màn hình ngoài).
  - Gọi API thật của Gemini/Claude/OpenAI/Groq/Deepgram (chưa có key).
  - Chất lượng chép lời tiếng Việt với giọng nói thật.
  - Gửi Notion/webhook thật.

## 8. Build lại từ mã nguồn (trên máy Windows này)

**Từ CMD hoặc PowerShell** (mở trong thư mục `NgheNho`): chạy `.\build.cmd`. Lệnh này gọi Git Bash giúp bạn.
Đừng gõ `bash build.sh` trong CMD: lệnh `bash` ở đó là WSL. Máy này chưa cài bản Linux nào cho WSL nên sẽ báo lỗi `execvpe(/bin/bash) failed`.

```bat
.\build.cmd                    :: unit test + APK release -> dist\Flowme-<phiên bản>.apk
.\build.cmd assembleDebug      :: chạy task Gradle tuỳ chọn
```

**Từ Git Bash** (hoặc terminal của Claude Code):

```bash
bash build.sh                                   # unit test + APK release: dist/Flowme-<phiên bản>.apk (hiện là 2.0.0)
bash build.sh assembleDebug assembleDebugAndroidTest   # bản debug + bộ test trên thiết bị
# Test trên máy ảo (cần mock server: python -I mock_ai_server.py ở 127.0.0.1:8765):
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w vn.nghenho.test/androidx.test.runner.AndroidJUnitRunner
```
Bộ công cụ (JDK 17, Android SDK 35, Gradle 8.11.1) nằm ở `%LOCALAPPDATA%\Android`. Khoá ký APK nằm ở `keystore/`. **Hãy sao lưu thư mục `keystore/`**, vì bản cập nhật sau phải được ký bằng đúng khoá này mới cài đè được lên bản cũ mà không mất dữ liệu.

## 9. Cấu trúc mã nguồn

```
app/src/main/java/vn/nghenho/
  service/RecorderService.kt   dịch vụ nghe nền, gom cuộc hội thoại, thông báo
  service/Controls.kt          ô Cài đặt nhanh, widget, khởi động lại, nút trên thông báo
  audio/Segmenter.kt           phát hiện tiếng nói + cắt đoạn (có unit test)
  audio/Audio.kt               mã hoá AAC/WAV, chuyển .aac → .m4a
  ai/Transcriber.kt            Gemini / Deepgram / OpenAI / Groq / Whisper tự host
  ai/Llm.kt, ai/ClaudeClient.kt  tóm tắt & hỏi đáp (Gemini, Claude, OpenAI-compatible)
  ai/Parse.kt                  đọc phản hồi + lời nhắc AI (có unit test)
  ai/Memory.kt                 “Hỏi trí nhớ”
  work/Pipeline.kt             hàng chờ offline → chép lời → tóm tắt (WorkManager)
  integrations/Integrations.kt Lịch, Notion, Webhook, Gmail, xuất dữ liệu
  data/Data.kt                 Room DB, cài đặt, mã hoá key, khôi phục sau sự cố
  ui/*                         giao diện Jetpack Compose (thích ứng màn hình gập)
```
