/**
 * The signed-in player's own numbers, read straight from /api/stats/me.
 *
 * Nothing is computed here. The scoreboard on the game tab and this page have to agree, and
 * the only way to guarantee that is for neither of them to do arithmetic — the server already
 * settled it. Rendered on each reveal rather than cached, since a round played on the game
 * tab moves these totals.
 */
(function () {
    "use strict";

    var el = {
        wallet: document.getElementById("profile-wallet"),
        score: document.getElementById("profile-score"),
        total: document.getElementById("profile-total"),
        accuracy: document.getElementById("profile-accuracy"),
        best: document.getElementById("profile-best"),
        dayStreak: document.getElementById("profile-day-streak"),
        streakNote: document.getElementById("profile-streak-note"),
        legacyNote: document.getElementById("profile-legacy-note"),
        badges: document.getElementById("profile-badges"),
        byAsset: document.getElementById("profile-by-asset"),
        recent: document.getElementById("profile-recent"),
        shareCard: document.getElementById("profile-share-card"),
        shareSquares: document.getElementById("profile-share-squares"),
        shareLine: document.getElementById("profile-share-line"),
        share: document.getElementById("profile-share"),
        shareText: document.getElementById("profile-share-text"),
        rank: document.getElementById("profile-rank"),
        rankMedal: document.getElementById("profile-rank-medal"),
        rankValue: document.getElementById("profile-rank-value"),
        insightsScope: document.getElementById("profile-insights-scope"),
        insightsBody: document.getElementById("profile-insights-body"),
    };

    /* ---- the rank medallion --------------------------------------------------------------- */

    /* Not fetched here. play-sidebar.js already asks /api/leaderboard for the column beside the
       chart and publishes what it found, the same bargain `candles:stats` makes — two callers
       reading one figure out of two responses can only end up disagreeing about it. Held in a
       variable because the event fires whenever the board reloads and this tab is usually not
       on screen when it does; without the last value a reveal would draw an empty disc. */
    var lastRank = null;

    var PODIUM = ["is-first", "is-second", "is-third"];

    function renderRank(rank) {
        el.rank.classList.remove("hidden", "is-first", "is-second", "is-third");
        /* No rank at all — signed out, or nothing recorded yet — is drawn as no medallion. An
           empty disc reads as something that failed to load, which is why the rail's tag makes
           the same choice with the same number. */
        if (!rank) {
            el.rank.classList.add("hidden");
            return;
        }
        if (rank <= PODIUM.length) el.rank.classList.add(PODIUM[rank - 1]);
        el.rankValue.textContent = "#" + rank;
        el.rankMedal.classList.toggle("is-wide", rank >= 100);
        el.rank.setAttribute("aria-label", "Hạng " + rank + " trên bảng xếp hạng. Mở bảng xếp hạng.");
    }

    document.addEventListener("candles:rank", function (event) {
        lastRank = event.detail.rank;
        renderRank(lastRank);
    });

    function pct(correct, total) {
        return total === 0 ? "–" : Math.round((correct / total) * 100) + "%";
    }

    function timeAgo(iso) {
        var mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
        if (mins < 1) return "vừa xong";
        if (mins < 60) return mins + " phút trước";
        var hours = Math.round(mins / 60);
        if (hours < 24) return hours + " giờ trước";
        return Math.round(hours / 24) + " ngày trước";
    }

    /* Earned badges first, then the ones still in reach. Ordering by state rather than by the
       catalogue's own order is what makes the section read as "what you have, and what is
       next" instead of a checklist with the wins scattered through it. */
    function renderBadges(rows) {
        el.badges.innerHTML = "";
        if (!rows || !rows.length) return;

        var ordered = rows.slice().sort(function (a, b) {
            if (a.earned !== b.earned) return a.earned ? -1 : 1;
            // Among the unearned, closest to done first — the next goal should be the one at
            // the front, not whichever happens to be cheapest to describe.
            return (b.progress / b.target) - (a.progress / a.target);
        });

        ordered.forEach(function (badge) {
            var card = document.createElement("div");
            card.className = "profile-badge" + (badge.earned ? " is-earned" : "");

            var state = document.createElement("span");
            state.className = "profile-badge-state";
            state.textContent = badge.earned ? "Đã đạt" : "Đang tiến";

            var name = document.createElement("span");
            name.className = "profile-badge-name";
            name.textContent = badge.name;

            var desc = document.createElement("span");
            desc.className = "profile-badge-desc";
            desc.textContent = badge.description;

            card.appendChild(state);
            card.appendChild(name);
            card.appendChild(desc);

            /* An earned badge shows no bar: a full bar beside a finished thing is noise, and
               the point of the bar is the distance still to go. */
            if (!badge.earned) {
                var count = document.createElement("span");
                count.className = "profile-badge-count";
                count.textContent = badge.progress + " / " + badge.target;

                var track = document.createElement("span");
                track.className = "profile-badge-track";
                var fill = document.createElement("span");
                fill.className = "profile-badge-fill";
                fill.style.width = (badge.target === 0 ? 0 : (badge.progress / badge.target) * 100) + "%";
                track.appendChild(fill);

                card.appendChild(count);
                card.appendChild(track);
            }

            el.badges.appendChild(card);
        });
    }

    function renderByAsset(rows) {
        el.byAsset.innerHTML = "";
        if (!rows.length) {
            el.byAsset.innerHTML = '<p class="profile-empty">Chưa có lượt đoán nào được ghi lại.</p>';
            return;
        }
        rows.forEach(function (row) {
            var card = document.createElement("div");
            card.className = "profile-asset";

            var name = document.createElement("span");
            name.className = "profile-asset-name";
            name.textContent = row.symbol;

            var rate = document.createElement("span");
            rate.className = "profile-asset-rate rolling";
            window.CandleRolling.update(rate, pct(row.correct, row.total));

            var count = document.createElement("span");
            count.className = "profile-asset-count";
            count.textContent = row.correct + "/" + row.total;

            // A bar reads at a glance in a way three numbers in a row do not.
            var track = document.createElement("span");
            track.className = "profile-asset-track";
            var fill = document.createElement("span");
            fill.className = "profile-asset-fill";
            fill.style.width = (row.total === 0 ? 0 : (row.correct / row.total) * 100) + "%";
            track.appendChild(fill);

            card.appendChild(name);
            card.appendChild(rate);
            card.appendChild(count);
            card.appendChild(track);
            el.byAsset.appendChild(card);
        });
    }

    function renderRecent(rows) {
        el.recent.innerHTML = "";
        if (!rows.length) {
            el.recent.innerHTML = '<p class="profile-empty">Lịch sử sẽ xuất hiện sau lượt đoán đầu tiên.</p>';
            return;
        }
        rows.forEach(function (row) {
            var item = document.createElement("div");
            item.className = "profile-guess " + (row.correct ? "is-correct" : "is-wrong");

            var mark = document.createElement("span");
            mark.className = "profile-guess-mark";
            mark.textContent = row.correct ? "✓" : "✕";

            var symbol = document.createElement("span");
            symbol.className = "profile-guess-symbol";
            symbol.textContent = row.symbol;

            var call = document.createElement("span");
            call.className = "profile-guess-call";
            // Show what was actually right too, so a miss says why it was a miss. A guess with
            // no direction is one the countdown ate — saying "đoán null" would be worse than
            // saying nothing, and calling it a wrong guess would be untrue.
            call.textContent = row.guessed
                ? (row.correct ? "đoán " + row.guessed
                               : "đoán " + row.guessed + " · thực tế " + row.actual)
                : "hết giờ · thực tế " + row.actual;

            var when = document.createElement("span");
            when.className = "profile-guess-when";
            when.textContent = timeAgo(row.at);

            item.appendChild(mark);
            item.appendChild(symbol);
            item.appendChild(call);
            item.appendChild(when);
            el.recent.appendChild(item);
        });
    }

    /* The streak the player can still act on today. A run that is alive but unplayed is the
       only state worth a line of its own — it is the one moment where opening the game changes
       the number, and saying so is the whole point of counting days. */
    function renderDayStreak(streak) {
        window.CandleRolling.update(el.dayStreak, streak.current);
        el.dayStreak.title = "Dài nhất " + streak.best + " ngày · đã chơi " + streak.daysPlayed + " ngày";

        var atRisk = streak.current > 0 && !streak.playedToday;
        el.streakNote.classList.toggle("hidden", !atRisk);
        if (atRisk) {
            el.streakNote.textContent =
                "Chuỗi " + streak.current + " ngày của bạn vẫn đang mở. Chơi một ván hôm nay để giữ nó.";
        }
    }

    /* ---- the share card ------------------------------------------------------------
       Standing rather than a round: score, accuracy, day streak, and the last five calls as
       squares. It follows the daily card's rule of carrying no asset and no dates, though
       for a different reason — there is no puzzle to spoil here, only a record to post, and
       naming the pairs would say which markets someone plays without adding anything.

       Recorded results only. A carried-over browser tally has no per-guess detail behind it,
       so squares drawn from it would be invented. */

    /* The text the button copies, rebuilt whenever the numbers behind it are. */
    var shareBody = "";

    function renderShare(data) {
        var results = (data.recent || []).slice(0, 5).map(function (g) { return g.correct; }).reverse();
        el.shareCard.classList.toggle("hidden", !results.length);
        if (!results.length) return;

        el.shareSquares.textContent = results
            .map(function (ok) { return ok ? "🟩" : "🟥"; }).join("");
        el.shareLine.textContent =
            data.score + " điểm · " + pct(data.correct, data.total) + " đúng · "
            + data.dayStreak.current + " ngày liên tiếp";
        shareBody = "Candle Guess — hồ sơ\n"
            + el.shareLine.textContent + "\n"
            + el.shareSquares.textContent + "\n"
            + window.location.origin;
    }

    async function copyShare() {
        try {
            await navigator.clipboard.writeText(shareBody);
            el.share.textContent = "Đã sao chép";
            setTimeout(function () { el.share.textContent = "Sao chép kết quả"; }, 2000);
        } catch (e) {
            /* Refused — an insecure origin, or a browser that wants a gesture it did not see.
               Putting the text on screen and selecting it is the difference between a share
               button that failed and one the player can still act on. */
            el.shareText.value = shareBody;
            el.shareText.classList.remove("hidden");
            el.shareText.select();
            el.share.textContent = "Chép thủ công ở dưới";
        }
    }

    function render(data) {
        var user = window.CandleAuth.getUser();
        el.wallet.textContent = user ? user.displayName : "";

        window.CandleRolling.update(el.score, data.score);
        window.CandleRolling.update(el.total, data.total);
        window.CandleRolling.update(el.accuracy, pct(data.correct, data.total));
        window.CandleRolling.update(el.best, data.bestStreak);

        renderDayStreak(data.dayStreak);
        /* Redrawn on every reveal from the value already in hand: the board may have last
           reported while this tab was hidden, and the class list survives nothing else here. */
        renderRank(lastRank);

        /* Say plainly which part of the total the server watched happen. Without this the
           carried-over figures look like they were all earned on this account. */
        var carried = data.total - data.recorded.total;
        el.legacyNote.classList.toggle("hidden", !data.legacyImported || carried <= 0);
        if (data.legacyImported && carried > 0) {
            el.legacyNote.textContent =
                "Trong đó " + carried + " lượt được mang lên từ dữ liệu lưu trên trình duyệt trước khi bạn "
                + "có tài khoản. " + data.recorded.total + " lượt được máy chủ ghi lại.";
        }

        renderBadges(data.achievements);
        renderByAsset(data.byAsset);
        renderRecent(data.recent);
        renderShare(data);
    }

    /* ---- habits ------------------------------------------------------------------------------

       The server decides what counts as a finding and orders them; this only turns each one into
       a sentence. Every rate is divided here from counts, once — the same rule the retention pane
       follows — and a finding carries no figures of its own, only which bucket to read, so the
       sentence and the table under it cannot disagree. */

    var TREND_LABEL = { RISING: "Sau nhịp tăng", FALLING: "Sau nhịp giảm", FLAT: "Chart đi ngang" };
    var TREND_PHRASE = { RISING: "sau một nhịp tăng", FALLING: "sau một nhịp giảm", FLAT: "khi chart đi ngang" };
    var SESSION_LABEL = { NIGHT: "Đêm (0–6h)", MORNING: "Sáng (6–12h)", AFTERNOON: "Chiều (12–18h)", EVENING: "Tối (18–24h)" };
    var SESSION_PHRASE = { NIGHT: "ban đêm", MORNING: "buổi sáng", AFTERNOON: "buổi chiều", EVENING: "buổi tối" };
    var SESSION_ORDER = ["MORNING", "AFTERNOON", "EVENING", "NIGHT"];
    var TREND_ORDER = ["RISING", "FALLING", "FLAT"];

    function share(part, whole) {
        return whole ? Math.round((part / whole) * 100) : 0;
    }

    function findBucket(list, field, key) {
        for (var i = 0; i < list.length; i++) if (list[i][field] === key) return list[i];
        return null;
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
                return "Bạn đoán kém nhất " + TREND_PHRASE[finding.key] + ": đúng " + share(b.correct, b.total) + "% ("
                    + b.correct + "/" + b.total + "), thấp hơn mức chung " + overall + "%, và chọn LONG "
                    + share(b.longCalls, b.total) + "% số lần." + trendAdvice(finding.key, share(b.longCalls, b.total));
            case "WEAK_SESSION":
                b = findBucket(data.sessions, "session", finding.key);
                return "Chơi " + SESSION_PHRASE[finding.key] + " bạn chỉ đúng " + share(b.correct, b.total) + "% ("
                    + b.correct + "/" + b.total + "), thấp hơn mức chung " + overall + "%.";
            case "TIMEOUTS":
                return finding.gapPoints + "% số lượt bạn để hết giờ. Hết giờ tính là sai, nên chọn một hướng vẫn tốt hơn bỏ trống.";
        }
        return null;
    }

    /* Only said where the direction split makes it true: "chasing the move" needs the calls to
       actually lean with the move, not merely to be wrong after one. */
    function trendAdvice(trend, longShare) {
        if (trend === "RISING" && longShare >= 65) return " Có thể bạn đang đuổi theo đà tăng khi nó sắp hết.";
        if (trend === "FALLING" && longShare <= 35) return " Có thể bạn đang bán theo khi đà giảm sắp hết.";
        if (trend === "FALLING" && longShare >= 65) return " Có thể bạn đang bắt đáy quá sớm.";
        return "";
    }

    function node(tag, className, text) {
        var el = document.createElement(tag);
        if (className) el.className = className;
        if (text !== undefined) el.textContent = text;
        return el;
    }

    function splitBar(label, leftLabel, leftShare, rightLabel) {
        var row = node("div", "pf-split");
        row.appendChild(node("span", "pf-split-label", label));
        var track = node("span", "pf-split-track");
        var fill = node("span", "pf-split-fill");
        fill.style.width = leftShare + "%";
        track.appendChild(fill);
        row.appendChild(track);
        row.appendChild(node("span", "pf-split-value", leftLabel + " " + leftShare + "% · " + rightLabel + " " + (100 - leftShare) + "%"));
        return row;
    }

    function bucketTable(caption, rows) {
        var wrap = node("div", "pf-buckets");
        wrap.appendChild(node("span", "side-eyebrow", caption));
        var table = node("table", "pf-bucket-table");
        var head = node("tr");
        ["", "Lượt", "Chọn LONG", "Đúng"].forEach(function (h, i) {
            head.appendChild(node("th", i ? "num" : "", h));
        });
        table.appendChild(head);
        rows.forEach(function (r) {
            var tr = node("tr");
            tr.appendChild(node("td", "", r.label));
            tr.appendChild(node("td", "num", String(r.total)));
            tr.appendChild(node("td", "num", r.total ? share(r.longCalls, r.total) + "%" : "–"));
            var correct = node("td", "num", r.total ? share(r.correct, r.total) + "%" : "–");
            if (r.weak) correct.classList.add("is-weak");
            tr.appendChild(correct);
            table.appendChild(tr);
        });
        wrap.appendChild(table);
        return wrap;
    }

    function renderInsights(data) {
        var body = el.insightsBody;
        body.innerHTML = "";
        var answered = data.calls.longCalls + data.calls.shortCalls;
        el.insightsScope.textContent = data.analysed
            ? data.analysed + " lượt gần nhất" + (data.analysed >= data.window ? " (tối đa " + data.window + ")" : "")
            : "";

        /* Findings first even when there are too few answered calls for the rest: the server only
           lets one through then — letting the clock run out — and that is exactly the player who
           most needs to read it. */
        var list = node("ul", "pf-findings");
        data.findings.slice(0, 3).forEach(function (f) {
            var text = sentence(f, data);
            if (text) list.appendChild(node("li", "pf-finding", text));
        });
        if (list.children.length) body.appendChild(list);

        if (!data.enough) {
            var need = data.minSample - answered;
            body.appendChild(node("p", "profile-empty",
                "Cần thêm " + need + " lượt đoán nữa để phân tích thói quen của bạn (" + answered + "/" + data.minSample
                + "). Ít hơn thế thì mọi \"thiên kiến\" đều có thể chỉ là may rủi."));
            var progress = node("span", "pf-split-track pf-insights-progress");
            var fill = node("span", "pf-split-fill");
            fill.style.width = share(answered, data.minSample) + "%";
            progress.appendChild(fill);
            body.appendChild(progress);
            return;
        }

        if (!list.children.length) {
            body.appendChild(node("p", "pf-findings-none",
                "Chưa thấy thói quen lệch rõ rệt nào trong " + answered + " lượt gần nhất. Cách bạn đọc chart đang khá cân bằng."));
        }

        var c = data.calls;
        var bars = node("div", "pf-splits");
        bars.appendChild(splitBar("Bạn chọn", "LONG", share(c.longCalls, answered), "SHORT"));
        bars.appendChild(splitBar("Nến thật", "Tăng", share(c.marketUp, answered), "Giảm"));
        body.appendChild(bars);

        var weakKeys = {};
        data.findings.forEach(function (f) { if (f.key) weakKeys[f.kind + ":" + f.key] = true; });

        var grid = node("div", "pf-bucket-grid");
        grid.appendChild(bucketTable("Theo diễn biến trước lượt đoán", TREND_ORDER.map(function (key) {
            var b = findBucket(data.trends, "trend", key);
            return { label: TREND_LABEL[key], total: b.total, correct: b.correct, longCalls: b.longCalls, weak: weakKeys["WEAK_TREND:" + key] };
        })));
        grid.appendChild(bucketTable("Theo buổi (giờ Việt Nam)", SESSION_ORDER.map(function (key) {
            var b = findBucket(data.sessions, "session", key);
            return { label: SESSION_LABEL[key], total: b.total, correct: b.correct, longCalls: b.longCalls, weak: weakKeys["WEAK_SESSION:" + key] };
        })));
        body.appendChild(grid);
    }

    /* Separate from the totals on purpose: a slow or failed read of habits must not hold up or
       blank the numbers above it, which are the part a player opened the tab for. */
    async function loadInsights() {
        try {
            var res = await window.CandleAuth.authFetch("/api/stats/me/insights");
            if (!res.ok) throw new Error(String(res.status));
            renderInsights(await res.json());
        } catch (e) {
            if (!el.insightsBody.children.length) {
                el.insightsBody.innerHTML = '<p class="profile-empty">Không tải được phần thói quen. Mở lại tab này để thử lại.</p>';
            }
        }
    }

    var loaded = false;

    /* On a first open that fails there is nothing on screen to fall back to, and the two
       list sections would sit empty with no explanation — indistinguishable from an account
       that has never played. Say so instead. */
    function showLoadFailure() {
        var message = '<p class="profile-empty">Không tải được thống kê. Kiểm tra kết nối rồi mở lại tab này.</p>';
        el.byAsset.innerHTML = message;
        el.recent.innerHTML = message;
    }

    async function load() {
        if (!window.CandleAuth.getUser()) return;
        loadInsights();
        try {
            var res = await window.CandleAuth.authFetch("/api/stats/me");
            if (res.ok) {
                render(await res.json());
                loaded = true;
                return;
            }
        } catch (e) {
            // Falls through to the same handling as a non-ok response.
        }
        // Once something has rendered, leaving it up beats replacing real numbers with an
        // error the player can do nothing about.
        if (!loaded) showLoadFailure();
    }

    el.share.addEventListener("click", copyShare);

    window.__initProfileView = load;
})();
