/**
 * Blog CRUD for the admin page: the list of posts, and the editor behind it.
 *
 * The editor keeps the post being edited in a plain object and re-renders the block list from
 * it, rather than reading values back out of the DOM on save. Blocks get added, removed and
 * moved, and reading a reordered list out of the document is where that kind of editor starts
 * disagreeing with itself.
 */
(function () {
    "use strict";

    var el = {
        section: document.getElementById("admin-blog"),
        list: document.getElementById("blog-list-admin"),
        editor: document.getElementById("blog-editor"),
        editorTitle: document.getElementById("blog-editor-title"),
        newBtn: document.getElementById("blog-new"),
        cancelBtn: document.getElementById("blog-cancel"),
        addText: document.getElementById("blog-add-text"),
        addImage: document.getElementById("blog-add-image"),
        blocks: document.getElementById("blog-blocks"),
        blocksEmpty: document.getElementById("blog-blocks-empty"),
        status: document.getElementById("admin-status"),
        f: {
            title: document.getElementById("f-title"),
            slug: document.getElementById("f-slug"),
            tags: document.getElementById("f-tags"),
            position: document.getElementById("f-position"),
            source: document.getElementById("f-source"),
            sourceUrl: document.getElementById("f-source-url"),
            coverImg: document.getElementById("f-cover-img"),
            imageCredit: document.getElementById("f-image-credit"),
            coverSvg: document.getElementById("f-cover-svg"),
            published: document.getElementById("f-published"),
        },
    };

    var editing = null;   // the post being edited, or null when the editor is closed
    var blocks = [];      // the block list, owned here rather than read back from the DOM

    function setStatus(text) {
        el.status.textContent = text || "";
    }

    async function api(path, options) {
        var res = await window.CandleAuth.authFetch("/api/admin/blog/posts" + path, options);
        if (res.status === 204) return null;
        var payload = res.status === 204 ? null : await res.json();
        if (!res.ok) throw new Error((payload && payload.message) || ("Máy chủ trả về " + res.status));
        return payload;
    }

    /* ---- list ---- */

    function summarise(post) {
        var tags = (post.tags || []).join(" · ") || "chưa gắn thẻ";
        var state = post.published ? "Đã đăng" : "Nháp";
        return tags + " · " + state + " · " + (post.body || []).length + " khối";
    }

    function row(post) {
        var item = document.createElement("div");
        item.className = "blog-admin-row" + (post.published ? "" : " is-draft");

        /* The cover is what makes a list of thirty titles scannable. A post without one gets
           the empty square rather than a shorter row, so the titles stay on one line. */
        var cover;
        if (post.coverImg) {
            cover = document.createElement("img");
            cover.src = post.coverImg;
            cover.alt = "";
            cover.loading = "lazy";
        } else {
            cover = document.createElement("div");
        }
        cover.className = "blog-admin-cover";
        item.appendChild(cover);

        var text = document.createElement("div");
        var title = document.createElement("p");
        title.className = "blog-admin-title";
        title.textContent = post.title;
        var meta = document.createElement("p");
        meta.className = "blog-admin-meta";
        meta.textContent = summarise(post);
        text.appendChild(title);
        text.appendChild(meta);

        var actions = document.createElement("div");
        actions.className = "blog-admin-actions";
        actions.appendChild(button("Sửa", function () { openEditor(post); }));
        actions.appendChild(button(post.published ? "Ẩn" : "Đăng", function () {
            togglePublished(post);
        }));
        actions.appendChild(button("Xoá", function () { remove(post); }, "danger-btn"));

        item.appendChild(text);
        item.appendChild(actions);
        return item;
    }

    function button(label, onClick, cls) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = cls || "ghost-btn";
        b.textContent = label;
        b.addEventListener("click", onClick);
        return b;
    }

    async function refresh() {
        try {
            var posts = await api("", {});
            el.list.innerHTML = "";
            if (!posts.length) {
                el.list.innerHTML = '<p class="block-empty">Chưa có bài nào.</p>';
                return;
            }
            posts.forEach(function (post) { el.list.appendChild(row(post)); });
        } catch (e) {
            setStatus("Không tải được danh sách: " + e.message);
        }
    }

    /* ---- write ---- */

    function payload() {
        return {
            slug: el.f.slug.value.trim() || null,
            title: el.f.title.value.trim(),
            tags: el.f.tags.value.split(",").map(function (t) { return t.trim(); })
                .filter(function (t) { return t; }),
            source: el.f.source.value.trim() || null,
            sourceUrl: el.f.sourceUrl.value.trim() || null,
            imageCredit: el.f.imageCredit.value.trim() || null,
            coverSvg: el.f.coverSvg.value.trim() || null,
            coverImg: el.f.coverImg.value.trim() || null,
            body: blocks,
            published: el.f.published.checked,
            position: Number(el.f.position.value) || 0,
        };
    }

    async function save(event) {
        event.preventDefault();
        var body = payload();
        if (!body.title) {
            setStatus("Bài viết cần có tiêu đề.");
            return;
        }
        setStatus("Đang lưu…");
        try {
            var options = {
                method: editing && editing.id ? "PUT" : "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            };
            await api(editing && editing.id ? "/" + editing.id : "", options);
            closeEditor();
            await refresh();
            setStatus("Đã lưu.");
        } catch (e) {
            setStatus("Lưu thất bại: " + e.message);
        }
    }

    async function togglePublished(post) {
        setStatus(post.published ? "Đang ẩn…" : "Đang đăng…");
        try {
            // PUT replaces the post, so everything it already has has to travel with the flag.
            var next = Object.assign({}, post, { published: !post.published });
            delete next.id;
            delete next.createdAt;
            delete next.updatedAt;
            await api("/" + post.id, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(next),
            });
            await refresh();
            setStatus(post.published ? "Đã ẩn khỏi trang blog." : "Đã đăng.");
        } catch (e) {
            setStatus("Không đổi được trạng thái: " + e.message);
        }
    }

    async function remove(post) {
        if (!window.confirm('Xoá hẳn bài "' + post.title + '"? Không hoàn tác được.')) return;
        setStatus("Đang xoá…");
        try {
            await api("/" + post.id, { method: "DELETE" });
            if (editing && editing.id === post.id) closeEditor();
            await refresh();
            setStatus("Đã xoá.");
        } catch (e) {
            setStatus("Xoá thất bại: " + e.message);
        }
    }

    /* ---- editor ---- */

    function openEditor(post) {
        editing = post || {};
        blocks = JSON.parse(JSON.stringify((post && post.body) || []));
        el.editorTitle.textContent = post ? "Sửa bài" : "Bài mới";
        el.f.title.value = (post && post.title) || "";
        el.f.slug.value = (post && post.slug) || "";
        el.f.tags.value = ((post && post.tags) || []).join(", ");
        el.f.position.value = post ? post.position : 0;
        el.f.source.value = (post && post.source) || "";
        el.f.sourceUrl.value = (post && post.sourceUrl) || "";
        el.f.coverImg.value = (post && post.coverImg) || "";
        el.f.imageCredit.value = (post && post.imageCredit) || "";
        el.f.coverSvg.value = (post && post.coverSvg) || "";
        el.f.published.checked = !!(post && post.published);
        renderBlocks();
        el.editor.classList.remove("hidden");
        el.f.title.focus();
    }

    function closeEditor() {
        editing = null;
        blocks = [];
        el.editor.classList.add("hidden");
        setStatus("");
    }

    function renderBlocks() {
        el.blocks.innerHTML = "";
        el.blocksEmpty.classList.toggle("hidden", blocks.length > 0);
        blocks.forEach(function (block, index) {
            el.blocks.appendChild(blockEditor(block, index));
        });
    }

    function blockEditor(block, index) {
        var wrap = document.createElement("div");
        wrap.className = "block";

        var head = document.createElement("div");
        head.className = "block-head";
        var kind = document.createElement("span");
        kind.className = "block-kind";
        kind.textContent = block.type === "image" ? "Ảnh" : "Đoạn văn";
        head.appendChild(kind);

        var tools = document.createElement("div");
        tools.className = "block-tools";
        tools.appendChild(button("↑", function () { move(index, -1); }));
        tools.appendChild(button("↓", function () { move(index, 1); }));
        tools.appendChild(button("✕", function () {
            blocks.splice(index, 1);
            renderBlocks();
        }, "danger-btn"));
        head.appendChild(tools);
        wrap.appendChild(head);

        if (block.type === "image") {
            var srcField = input("URL ảnh", block.src || "", function (v) { block.src = v; });
            /* Picking from the library fills the dimensions too. Those drive the image's
               reserved space on the blog, and typing them by hand is both tedious and the
               kind of thing that ends up wrong and shifts the layout as the page loads. */
            srcField.appendChild(button("Chọn từ thư viện", function () {
                if (!window.CandleMedia) return;
                window.CandleMedia.open(function (media) {
                    block.src = media.deliveryUrl;
                    block.w = media.width;
                    block.h = media.height;
                    renderBlocks();
                    el.editor.scrollIntoView({ block: "start", behavior: "smooth" });
                });
            }));
            wrap.appendChild(srcField);
            wrap.appendChild(input("Mô tả (alt)", block.alt || "", function (v) { block.alt = v; }));
            var size = document.createElement("div");
            size.className = "block-size";
            size.appendChild(input("Rộng", block.w || "", function (v) { block.w = Number(v) || undefined; }, "number"));
            size.appendChild(input("Cao", block.h || "", function (v) { block.h = Number(v) || undefined; }, "number"));
            wrap.appendChild(size);
        } else {
            var label = document.createElement("label");
            label.className = "field field-wide";
            label.textContent = "Nội dung";
            var area = document.createElement("textarea");
            area.rows = 4;
            area.value = block.text || "";
            area.addEventListener("input", function () { block.text = area.value; });
            label.appendChild(area);
            wrap.appendChild(label);
        }
        return wrap;
    }

    function input(label, value, onInput, type) {
        var wrap = document.createElement("label");
        wrap.className = "field field-wide";
        wrap.textContent = label;
        var field = document.createElement("input");
        field.type = type || "text";
        field.value = value;
        field.addEventListener("input", function () { onInput(field.value); });
        wrap.appendChild(field);
        return wrap;
    }

    function move(index, delta) {
        var to = index + delta;
        if (to < 0 || to >= blocks.length) return;
        var moved = blocks.splice(index, 1)[0];
        blocks.splice(to, 0, moved);
        renderBlocks();
    }

    el.newBtn.addEventListener("click", function () { openEditor(null); });
    el.cancelBtn.addEventListener("click", closeEditor);
    el.editor.addEventListener("submit", save);
    el.addText.addEventListener("click", function () {
        blocks.push({ type: "text", text: "" });
        renderBlocks();
    });
    el.addImage.addEventListener("click", function () {
        blocks.push({ type: "image", src: "", alt: "" });
        renderBlocks();
    });

    /* admin.js decides whether this account is an admin; this only reacts to that. */
    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) refresh();
    });
})();
