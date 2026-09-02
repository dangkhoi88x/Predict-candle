/**
 * "Tâm Lý Giao Dịch" tab — static tip cards summarizing trading-psychology principles
 * (position sizing, drawdown discipline, process over outcome...), paraphrased in Vietnamese
 * from a public thread by trader Mizer (@MizerXBT) on X, plus a real trade case study he
 * shared. Pure content, no chart/algorithm involved — see index.html for source credit.
 */
(function () {
    "use strict";

    var TIPS = [
        {
            title: "Size quyết định tâm trí",
            body: "Đặt khối lượng đủ nhỏ để dù lệnh thua cũng không loại bạn khỏi cuộc chơi. Size quá lớn phá huỷ khả năng suy nghĩ khách quan trước khi giá kịp đi đâu.",
        },
        {
            title: "Chấp nhận uPnL âm",
            body: "Trước khi đóng lệnh lỗ, tự hỏi: kịch bản đã thay đổi hay chỉ là mình khó chịu với con số đỏ? Đóng vì thesis sai thì ổn, đóng chỉ vì đau là sai lầm tâm lý.",
        },
        {
            title: "Invalidate vì cấu trúc, không vì đau",
            body: "Chỉ thoát lệnh khi luận điểm ban đầu không còn đúng, không phải vì cảm giác khó chịu khi giữ lệnh.",
        },
        {
            title: "Đừng đùa với thanh lý",
            body: "Đặt đòn bẩy/size sao cho điểm thanh lý luôn nằm xa hơn điểm invalidation của bạn, không phải ngược lại.",
        },
        {
            title: "Khiêm tốn sau chuỗi thắng",
            body: "Chuỗi thắng dễ sinh tự tin thái quá. Không ai miễn nhiễm với thua lỗ, kể cả sau một chuỗi thắng đẹp.",
        },
        {
            title: "Giấc ngủ là một phần của lợi thế",
            body: "Nếu một lệnh khiến bạn mất ngủ, size hoặc kế hoạch đang sai — giảm, hedge hoặc đóng lệnh đó.",
        },
        {
            title: "Tắt tiếng ồn",
            body: "Nếu newsfeed khiến bạn bị thiên lệch hoặc vội vàng ra quyết định, hãy tắt nó và quay về kế hoạch ban đầu.",
        },
        {
            title: "Dùng khoảng dừng chiến lược",
            body: "Sau một cú thắng/thua lớn hoặc chuỗi lỗi liên tiếp, nghỉ 24-72 giờ để review thay vì ép giao dịch chất lượng thấp.",
        },
        {
            title: "Bỏ áp lực, giữ quy trình",
            body: "Càng tự tạo áp lực \"phải thắng ngay\", giao dịch càng tệ. Size sao cho bạn giữ được sự bình thản.",
        },
        {
            title: "Sau cú vấp, quay về cơ bản",
            body: "Size nhỏ, quy tắc rõ ràng, thực thi sạch sẽ. Xây lại sự tự tin trước, tăng size sau.",
        },
        {
            title: "Coi trọng quy trình hơn kết quả",
            body: "Quy trình tốt vẫn có thể ra kết quả xấu (và ngược lại) trong ngắn hạn — đánh giá lệnh theo chất lượng setup, không chỉ theo lời/lỗ.",
        },
        {
            title: "Chấp nhận sự không chắc chắn",
            body: "Không cần lúc nào cũng vào lệnh hay dự đoán hoàn hảo. Vạch nhiều kịch bản kèm điểm invalidation; không chắc thì giảm size hoặc đứng ngoài.",
        },
    ];

    var CASE_STUDY = {
        title: "Case study: lệnh long BTC $40M trước bầu cử Trump",
        body: "30/10/2024, Mizer mở long BTC $40M ở $72,010 với điểm invalidation $64K. Giá giảm về gần $67K khiến uPnL âm khoảng $2.5M, nhưng vì thesis (Trump thắng cử) chưa hề thay đổi nên anh giữ vững lệnh thay vì thoát vì sợ hãi. Kết quả: chốt lời khoảng $11M chỉ hơn 1 tuần sau. Bài học: cùng một lệnh, chỉ có tâm lý người cầm lệnh thay đổi cách nhìn nhận nó — bản thân lệnh không hề đổi khác.",
    };

    function buildCard(tip, index) {
        var card = document.createElement("div");
        card.className = "psych-card";

        var number = document.createElement("span");
        number.className = "psych-card-number";
        number.textContent = String(index + 1).padStart(2, "0");

        var title = document.createElement("h3");
        title.className = "psych-card-title";
        title.textContent = tip.title;

        var body = document.createElement("p");
        body.className = "psych-card-body";
        body.textContent = tip.body;

        card.appendChild(number);
        card.appendChild(title);
        card.appendChild(body);
        return card;
    }

    function buildCaseStudyCard(entry) {
        var card = document.createElement("div");
        card.className = "psych-card psych-case-study";

        var number = document.createElement("span");
        number.className = "psych-card-number";
        number.textContent = "CASE STUDY";

        var title = document.createElement("h3");
        title.className = "psych-card-title";
        title.textContent = entry.title;

        var body = document.createElement("p");
        body.className = "psych-card-body";
        body.textContent = entry.body;

        card.appendChild(number);
        card.appendChild(title);
        card.appendChild(body);
        return card;
    }

    async function init() {
        var tips = await window.CandleContent.load("psychology", TIPS);
        var grid = document.getElementById("psychology-grid");
        // The case study is a one-off layout rather than a list entry, so it stays in code.
        grid.appendChild(buildCaseStudyCard(CASE_STUDY));
        tips.forEach(function (tip, index) {
            grid.appendChild(buildCard(tip, index));
        });
    }

    init();
})();
