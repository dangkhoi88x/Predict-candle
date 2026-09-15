/**
 * The group reminders card on the challenges pane: what today's two messages say, where they go,
 * what has already gone out, a way to find a group's chat id, and a way to post one now.
 *
 * The previews are drawn from the server's own text — the same string the bot sends — rather than
 * rebuilt here, so the card cannot show something the group will not see. That text is Telegram's
 * HTML subset, which the bot's messages use only for <b>; it is drawn with createElement, never
 * innerHTML, since a display name inside it is somebody's own writing.
 */
(function () {
    "use strict";

    var el = {
        card: document.getElementById("telegram-card"),
        state: document.getElementById("telegram-state"),
        where: document.getElementById("telegram-where"),
        morning: document.getElementById("telegram-morning"),
        evening: document.getElementById("telegram-evening"),
        morningLabel: document.getElementById("telegram-morning-label"),
        eveningLabel: document.getElementById("telegram-evening-label"),
        sendMorning: document.getElementById("telegram-send-morning"),
        sendEvening: document.getElementById("telegram-send-evening"),
        find: document.getElementById("telegram-find"),
        chatsWrap: document.getElementById("telegram-chats-wrap"),
        chats: document.querySelector("#telegram-chats tbody"),
        status: document.getElementById("admin-status"),
    };
    if (!el.card) return;

    var current = null;

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/telegram" + path, options);
        var payload = await res.json().catch(function () { return null; });
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    function decode(text) {
        return text.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
    }

    /** Telegram HTML with only <b> in it, as nodes. */
    function drawBubble(target, preview) {
        target.innerHTML = "";
        target.classList.toggle("is-problem", !!preview.problem);
        if (preview.problem) {
            target.textContent = preview.problem;
            return;
        }
        preview.html.split(/(<b>|<\/b>)/).reduce(function (bold, part) {
            if (part === "<b>") return true;
            if (part === "</b>") return false;
            if (part) target.appendChild(element(bold ? "b" : "span", null, decode(part)));
            return bold;
        }, false);
        if (preview.buttonText) {
            var button = element("span", "telegram-bubble-button", preview.buttonText);
            button.title = preview.buttonUrl;
            target.appendChild(button);
        }
    }

    function cronTime(cron) {
        if (!cron || cron === "-") return "tắt";
        var parts = cron.split(/\s+/);
        return parts.length >= 3 && /^\d+$/.test(parts[1]) && /^\d+$/.test(parts[2])
            ? ("0" + parts[2]).slice(-2) + ":" + ("0" + parts[1]).slice(-2)
            : cron;
    }

    function sentTo(kind) {
        return current.sent.filter(function (s) { return s.kind === kind; }).map(function (s) { return s.chatId; });
    }

    function render(status) {
        current = status;
        el.state.textContent = status.enabled ? "đang bật · Thử thách #" + status.roundNumber
            : status.botConfigured ? "chưa bật" : "chưa có bot";

        if (!status.botConfigured) {
            el.where.textContent = "Chưa có TELEGRAM_BOT_TOKEN trên máy chủ, nên bot không gửi được gì.";
        } else if (!status.chatIds.length) {
            el.where.textContent = "Chưa có nhóm nào trong TELEGRAM_DAILY_CHAT_IDS. Tìm chat id của nhóm ở dưới, "
                + "thêm vào biến đó trên Render — chỉ những nhóm đã đồng ý nhận nhắc.";
        } else {
            var times = [status.morningCron, status.eveningCron]
                .filter(function (cron) { return cron && cron !== "-"; }).map(cronTime);
            el.where.textContent = "Gửi tới " + status.chatIds.join(", ")
                + (times.length ? " lúc " + times.join(" và ") + " giờ Việt Nam." : " — cả hai lịch đang tắt, chỉ gửi khi bấm.");
        }

        drawBubble(el.morning, status.morning);
        drawBubble(el.evening, status.evening);
        el.morningLabel.textContent = "Tin sáng · " + cronTime(status.morningCron) + label("MORNING");
        el.eveningLabel.textContent = "Tin tối · " + cronTime(status.eveningCron) + label("EVENING");
        el.sendMorning.disabled = !status.enabled;
        el.sendEvening.disabled = !status.enabled;
    }

    function label(kind) {
        var sent = sentTo(kind);
        if (!sent.length) return "";
        return sent.length >= current.chatIds.length ? " · hôm nay đã gửi" : " · đã gửi " + sent.length + "/" + current.chatIds.length + " nhóm";
    }

    async function load() {
        try {
            render(await api(""));
        } catch (e) {
            el.state.textContent = "lỗi";
            el.where.textContent = "Không tải được: " + e.message;
        }
    }

    var OUTCOMES = { SENT: "đã gửi", ALREADY_SENT: "hôm nay đã gửi rồi", FAILED: "lỗi" };

    async function send(kind, button) {
        var name = kind === "MORNING" ? "tin sáng" : "tin tối";
        /* Sending now is the day's message, not an extra one: the schedule skips a chat that
           already has it. Worth saying before the button is pressed, not after. */
        if (!window.confirm("Gửi " + name + " của hôm nay tới " + current.chatIds.length + " nhóm ngay bây giờ?\n"
                + "Nhóm nào đã nhận thì bỏ qua, và lịch tự động sẽ không gửi lại " + name + " hôm nay.")) return;
        button.disabled = true;
        try {
            var results = await api("/send?kind=" + kind, { method: "POST" });
            el.status.textContent = "Telegram · " + name + ": " + results.map(function (r) {
                return r.chatId + " " + OUTCOMES[r.outcome] + (r.error ? " (" + r.error + ")" : "");
            }).join(" · ");
            await load();
        } catch (e) {
            el.status.textContent = e.message;
            button.disabled = false;
        }
    }

    async function findChats() {
        el.find.disabled = true;
        try {
            var chats = await api("/chats");
            el.chats.innerHTML = "";
            if (!chats.length) {
                var row = element("tr");
                var cell = element("td", null, "Bot chưa nghe thấy nhóm nào trong 24 giờ qua. Thêm bot vào nhóm hoặc gõ /start trong nhóm, rồi bấm lại.");
                cell.colSpan = 4;
                row.appendChild(cell);
                el.chats.appendChild(row);
            }
            chats.forEach(function (chat) {
                var row = element("tr");
                var id = element("td", "num", String(chat.id));
                id.style.fontFamily = "var(--mono)";
                row.appendChild(id);
                row.appendChild(element("td", null, chat.type));
                row.appendChild(element("td", null, chat.title || "—"));
                row.appendChild(element("td", null, current && current.chatIds.indexOf(chat.id) !== -1 ? "có" : "chưa"));
                el.chats.appendChild(row);
            });
            el.chatsWrap.classList.remove("hidden");
        } catch (e) {
            el.status.textContent = e.message;
        } finally {
            el.find.disabled = false;
        }
    }

    el.sendMorning.addEventListener("click", function () { send("MORNING", el.sendMorning); });
    el.sendEvening.addEventListener("click", function () { send("EVENING", el.sendEvening); });
    el.find.addEventListener("click", findChats);

    document.addEventListener("candles:admin", function (event) {
        if (event.detail.admin) load();
    });
})();
