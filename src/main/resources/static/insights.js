/**
 * Turns a finding from /api/stats/me/insights into a sentence, for the two places that show one:
 * the profile's habits section and the lesson card after the daily.
 *
 * One module so the two cannot phrase the same finding two ways. It computes nothing the server
 * did not already decide — which findings exist and their order are the server's — and only
 * divides counts into the percentages it writes, reading them from the bucket a finding names.
 */
(function () {
    "use strict";

    var TREND_PHRASE = { RISING: "sau một nhịp tăng", FALLING: "sau một nhịp giảm", FLAT: "khi chart đi ngang" };
    var SESSION_PHRASE = { NIGHT: "ban đêm", MORNING: "buổi sáng", AFTERNOON: "buổi chiều", EVENING: "buổi tối" };

    function share(part, whole) {
        return whole ? Math.round((part / whole) * 100) : 0;
    }

    function findBucket(list, field, key) {
        for (var i = 0; i < (list || []).length; i++) if (list[i][field] === key) return list[i];
        return null;
    }

    function patternName(id) {
        return window.CandlePatterns ? window.CandlePatterns.nameOf(id) : id;
    }

    /* Only said where the direction split makes it true: "chasing the move" needs the calls to
       actually lean with the move, not merely to be wrong after one. */
    function trendAdvice(trend, longShare) {
        if (trend === "RISING" && longShare >= 65) return " Có thể bạn đang đuổi theo đà tăng khi nó sắp hết.";
        if (trend === "FALLING" && longShare <= 35) return " Có thể bạn đang bán theo khi đà giảm sắp hết.";
        if (trend === "FALLING" && longShare >= 65) return " Có thể bạn đang bắt đáy quá sớm.";
        return "";
    }

    function sentence(finding, data) {
        var c = data.calls;
        var answered = c.longCalls + c.shortCalls;
        var overall = share(c.correctLong + c.correctShort, answered);
        var b;
        switch (finding.kind) {
            case "LONG_BIAS":
                return "Bạn nghiêng về LONG: chọn LONG " + share(c.longCalls, answered) + "% số lượt, trong khi nến thật chỉ tăng "
                    + share(c.marketUp, answered) + "%. Trước khi bấm, thử tự hỏi chart có thật sự đang yếu đi không.";
            case "SHORT_BIAS":
                return "Bạn nghiêng về SHORT: chọn SHORT " + share(c.shortCalls, answered) + "% số lượt, trong khi nến thật chỉ giảm "
                    + share(c.marketDown, answered) + "%. Thị trường không giảm thường xuyên như bạn nghĩ.";
            case "WEAK_TREND":
                b = findBucket(data.trends, "trend", finding.key);
                if (!b) return null;
                return "Bạn đoán kém nhất " + TREND_PHRASE[finding.key] + ": đúng " + share(b.correct, b.total) + "% ("
                    + b.correct + "/" + b.total + "), thấp hơn mức chung " + overall + "%, và chọn LONG "
                    + share(b.longCalls, b.total) + "% số lần." + trendAdvice(finding.key, share(b.longCalls, b.total));
            case "WEAK_SESSION":
                b = findBucket(data.sessions, "session", finding.key);
                if (!b) return null;
                return "Chơi " + SESSION_PHRASE[finding.key] + " bạn chỉ đúng " + share(b.correct, b.total) + "% ("
                    + b.correct + "/" + b.total + "), thấp hơn mức chung " + overall + "%.";
            case "WEAK_PATTERN":
                b = findBucket(data.patterns, "pattern", finding.key);
                if (!b) return null;
                return "Khi chart vừa có mẫu " + patternName(finding.key) + ", bạn chỉ đúng " + share(b.correct, b.total)
                    + "% (" + b.correct + "/" + b.total + "), thấp hơn mức chung " + overall + "%. Nên xem lại mẫu này.";
            case "TIMEOUTS":
                return finding.gapPoints + "% số lượt bạn để hết giờ. Hết giờ tính là sai, nên chọn một hướng vẫn tốt hơn bỏ trống.";
        }
        return null;
    }

    /** Resolves to the insights payload, or null signed out or on any failure — callers fall back, never throw. */
    async function load() {
        if (!window.CandleAuth || !window.CandleAuth.getUser()) return null;
        try {
            var res = await window.CandleAuth.authFetch("/api/stats/me/insights");
            if (!res.ok) return null;
            var data = await res.json();
            if (window.CandlePatterns) await window.CandlePatterns.whenLoaded();
            return data;
        } catch (e) {
            return null;
        }
    }

    window.CandleInsights = { share: share, findBucket: findBucket, patternName: patternName, sentence: sentence, load: load };
})();
