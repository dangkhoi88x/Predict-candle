/**
 * Editing for the three content libraries: candlestick patterns, chart patterns and the
 * psychology notes.
 *
 * The two pattern kinds are backed by matchers in Java, found by the entry's key. So this
 * screen offers wording, ordering and visibility for them, and no way to add, remove or
 * re-key one — the server refuses those anyway, and an interface that offers a button the
 * server will reject is worse than one that does not have the button.
 *
 * The illustration data (a pattern's candles, a chart pattern's points) is hand-tuned drawing
 * input, not prose. It is kept behind a collapsed JSON field: reachable when it genuinely
 * needs changing, out of the way the rest of the time. Fields that are not on this form
 * survive a save untouched, because the body is edited in place rather than rebuilt.
 */
(function () {
    "use strict";

    var LABEL = {
        "candle-pattern": "mẫu nến",
        "technical-pattern": "mẫu hình kỹ thuật",
        "psychology": "ghi chú tâm lý",
    };

    var el = {
        section: document.getElementById("admin-content"),
        pill: document.getElementById("content-kind-pill"),
        newBtn: document.getElementById("content-new"),
        note: document.getElementById("content-note"),
        list: document.getElementById("content-list"),
        editor: document.getElementById("content-editor"),
        editorTitle: document.getElementById("content-editor-title"),
        cancel: document.getElementById("content-cancel"),
        howto: document.getElementById("c-howto"),
        howtoBar: document.getElementById("c-howto-bar"),
        addStep: document.getElementById("c-add-step"),
        rawWrap: document.getElementById("c-raw-wrap"),
        status: document.getElementById("admin-status"),
        f: {
            title: document.getElementById("c-title"),
            key: document.getElementById("c-key"),
            keyLock: document.getElementById("c-key-lock"),
            position: document.getElementById("c-position"),
            tags: document.getElementById("c-tags"),
            summary: document.getElementById("c-summary"),
            published: document.getElementById("c-published"),
            raw: document.getElementById("c-raw"),
        },
    };

    var kind = "candle-pattern";
    var rows = [];
    var editing = null;
    var body = {};        // the entry itself, edited in place so unknown keys survive
    var steps = [];       // howTo, owned here for the same reason as the blog's blocks

    function setStatus(text) {
        el.status.textContent = text || "";
    }

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/content" + path, options);
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    function button(label, onClick, cls) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = cls || "ghost-btn";
        b.textContent = label;
        b.addEventListener("click", onClick);
        return b;
    }

    /* ---- list ---- */

    async function refresh() {
        setStatus("Đang tải…");
        try {
            rows = await api("/" + kind, {});
            var editable = rows.length ? rows[0].editableKey : false;
            el.newBtn.classList.toggle("hidden", !editable);
            el.note.textContent = editable
                ? rows.length + " " + LABEL[kind] + " — thêm, sửa và xoá được."
                : rows.length + " " + LABEL[kind] + " — sửa được nội dung, nhưng không thêm/xoá:"
                    + " mỗi mục gắn với một matcher trong mã nguồn.";
            render();
            setStatus("");
        } catch (e) {
            setStatus("Không tải được: " + e.message);
        }
    }

    function render() {
        el.list.innerHTML = "";
        rows.forEach(function (item) {
            var row = document.createElement("div");
            row.className = "blog-admin-row" + (item.published ? "" : " is-draft");

            var text = document.createElement("div");
            var title = document.createElement("p");
            title.className = "blog-admin-title";
            title.textContent = item.title;
            var meta = document.createElement("p");
            meta.className = "blog-admin-meta";
            meta.textContent = item.itemKey + " · " + (item.published ? "Hiển thị" : "Đang ẩn")
                + " · " + ((item.body && item.body.howTo) || []).length + " ý";
            text.appendChild(title);
            text.appendChild(meta);

            var actions = document.createElement("div");
            actions.className = "blog-admin-actions";
            actions.appendChild(button("Sửa", function () { open(item); }));
            actions.appendChild(button(item.published ? "Ẩn" : "Hiện", function () {
                togglePublished(item);
            }));
            if (item.editableKey) {
                actions.appendChild(button("Xoá", function () { remove(item); }, "danger-btn"));
            }

            row.appendChild(text);
            row.appendChild(actions);
            el.list.appendChild(row);
        });
    }

    /* ---- editor ---- */

    function open(item) {
        editing = item;
        body = item ? JSON.parse(JSON.stringify(item.body || {})) : {};
        steps = Array.isArray(body.howTo) ? body.howTo.slice() : [];

        var editableKey = item ? item.editableKey : true;
        el.editorTitle.textContent = item ? "Sửa mục" : "Mục mới";
        el.f.title.value = item ? item.title : "";
        el.f.key.value = item ? item.itemKey : "";
        el.f.key.disabled = !editableKey;
        el.f.keyLock.classList.toggle("hidden", editableKey);
        el.f.position.value = item ? item.position : rows.length;
        el.f.tags.value = (body.tags || []).join(", ");
        el.f.summary.value = body.summary || body.body || "";
        el.f.published.checked = item ? item.published : true;

        // Psychology notes have no bullet list and no illustration; hiding the fields is
        // clearer than showing two empty sections that never apply to them.
        var isNote = kind === "psychology";
        el.howtoBar.classList.toggle("hidden", isNote);
        el.howto.classList.toggle("hidden", isNote);
        el.rawWrap.classList.toggle("hidden", isNote);
        el.f.raw.value = isNote ? "" : JSON.stringify(illustration(), null, 1);

        renderSteps();
        el.editor.classList.remove("hidden");
        el.f.title.focus();
    }

    function illustration() {
        if (Array.isArray(body.candles)) return { candles: body.candles };
        if (Array.isArray(body.points)) return { points: body.points };
        return {};
    }

    function close() {
        editing = null;
        el.editor.classList.add("hidden");
        setStatus("");
    }

    function renderSteps() {
        el.howto.innerHTML = "";
        steps.forEach(function (step, index) {
            var wrap = document.createElement("div");
            wrap.className = "block";

            var head = document.createElement("div");
            head.className = "block-head";
            var kindLabel = document.createElement("span");
            kindLabel.className = "block-kind";
            kindLabel.textContent = "Ý " + (index + 1);
            head.appendChild(kindLabel);

            var tools = document.createElement("div");
            tools.className = "block-tools";
            tools.appendChild(button("↑", function () { move(index, -1); }));
            tools.appendChild(button("↓", function () { move(index, 1); }));
            tools.appendChild(button("✕", function () {
                steps.splice(index, 1);
                renderSteps();
            }, "danger-btn"));
            head.appendChild(tools);
            wrap.appendChild(head);

            var area = document.createElement("textarea");
            area.rows = 2;
            area.value = step;
            area.addEventListener("input", function () { steps[index] = area.value; });
            wrap.appendChild(area);
            el.howto.appendChild(wrap);
        });
    }

    function move(index, delta) {
        var to = index + delta;
        if (to < 0 || to >= steps.length) return;
        steps.splice(to, 0, steps.splice(index, 1)[0]);
        renderSteps();
    }

    async function save(event) {
        event.preventDefault();
        var title = el.f.title.value.trim();
        if (!title) {
            setStatus("Cần có tên hiển thị.");
            return;
        }

        if (kind === "psychology") {
            body.title = title;
            body.body = el.f.summary.value.trim();
        } else {
            body.name = title;
            body.id = el.f.key.value.trim() || body.id;
            body.summary = el.f.summary.value.trim();
            body.tags = el.f.tags.value.split(",").map(function (t) { return t.trim(); })
                .filter(function (t) { return t; });
            body.howTo = steps;

            // Merged rather than assigned: the illustration field only carries candles or
            // points, and overwriting the body with it would drop everything else.
            try {
                Object.assign(body, JSON.parse(el.f.raw.value || "{}"));
            } catch (e) {
                setStatus("Dữ liệu minh hoạ không phải JSON hợp lệ — chưa lưu.");
                return;
            }
        }

        var request = {
            itemKey: el.f.key.value.trim() || null,
            title: title,
            body: body,
            position: Number(el.f.position.value) || 0,
            published: el.f.published.checked,
        };

        setStatus("Đang lưu…");
        try {
            if (editing) {
                await api("/" + editing.id, {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(request),
                });
            } else {
                await api("/" + kind, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(request),
                });
            }
            close();
            await refresh();
            setStatus("Đã lưu.");
        } catch (e) {
            setStatus("Lưu thất bại: " + e.message);
        }
    }

    async function togglePublished(item) {
        try {
            await api("/" + item.id, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    itemKey: item.itemKey,
                    title: item.title,
                    body: item.body,
                    position: item.position,
                    published: !item.published,
                }),
            });
            await refresh();
        } catch (e) {
            setStatus("Không đổi được trạng thái: " + e.message);
        }
    }

    async function remove(item) {
        if (!window.confirm('Xoá "' + item.title + '"? Không hoàn tác được.')) return;
        try {
            await api("/" + item.id, { method: "DELETE" });
            if (editing && editing.id === item.id) close();
            await refresh();
            setStatus("Đã xoá.");
        } catch (e) {
            setStatus("Xoá thất bại: " + e.message);
        }
    }

    /* ---- wiring ---- */

    Array.prototype.slice.call(el.pill.querySelectorAll(".pill-option")).forEach(function (btn) {
        btn.addEventListener("click", function () {
            if (btn.classList.contains("active")) return;
            el.pill.querySelectorAll(".pill-option").forEach(function (b) {
                b.classList.toggle("active", b === btn);
            });
            kind = btn.dataset.kind;
            close();
            refresh();
        });
    });

    el.newBtn.addEventListener("click", function () { open(null); });
    el.cancel.addEventListener("click", close);
    el.addStep.addEventListener("click", function () {
        steps.push("");
        renderSteps();
    });
    el.editor.addEventListener("submit", save);

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) refresh();
    });
})();
