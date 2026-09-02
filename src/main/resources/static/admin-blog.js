/**
 * Blog CRUD for the admin page: the list of posts, and the editor behind it.
 *
 * The body is written in one contenteditable surface — type paragraphs, drop images in at the
 * caret — and read back out of that DOM on save as the same block array the database has
 * always held: `{type:"text", text}` and `{type:"image", src, alt, w, h}`.
 *
 * Reading the DOM back is the thing a block builder must not do, because a list you can
 * reorder has an order in two places. Here there is only one place: the surface *is* the
 * document order, and there is no separate array to drift from it.
 *
 * What is deliberately absent is bold, italic and links. The stored block holds a plain
 * string, so an editor offering them would have to either drop them on save or start storing
 * markup — and storing markup means `blog.js` renders with innerHTML instead of textContent,
 * on a public page, with a `body` column the server does not validate. Growing the block
 * format is the way to add them, not loosening the renderer.
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
        addImage: document.getElementById("blog-add-image"),
        body: document.getElementById("blog-body"),
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
    /* The writing surface is the source of truth while the editor is open, and the block
       array is produced from it on save. That is the opposite of the old block builder, and
       it is only safe because the surface holds nothing that a block cannot carry: a
       paragraph is text, a figure is an image, and there is no third thing to lose. */

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
            body: readBody(),
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
        writeBody((post && post.body) || []);
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
        el.editor.classList.remove("hidden");
        el.f.title.focus();
    }

    function closeEditor() {
        editing = null;
        el.body.innerHTML = "";
        el.editor.classList.add("hidden");
        setStatus("");
    }

    /* ---- the writing surface ----------------------------------------------------------
       Blocks in, blocks out. Nothing between those two functions is stored: the markup here
       exists only for the duration of an edit, which is what keeps this from becoming an
       HTML-storing editor and dragging a sanitiser in behind it. blog.js still renders every
       paragraph with textContent. */

    /** blocks -> the surface. */
    function writeBody(body) {
        el.body.innerHTML = "";
        (body || []).forEach(function (block) {
            el.body.appendChild(block.type === "image" ? figureFor(block) : paragraphFor(block.text));
        });
        // Always leave somewhere to type, or an empty post has no caret to place.
        if (!el.body.firstChild) el.body.appendChild(paragraphFor(""));
    }

    /**
     * The surface -> blocks, in document order.
     *
     * Recursive because browsers wrap things unpredictably: typing after an image can leave a
     * div holding both a figure and text, and a flat pass over childNodes would swallow the
     * figure into the paragraph's textContent.
     */
    function readBody() {
        var out = [];
        collect(el.body, out);
        return out;
    }

    function collect(node, out) {
        Array.prototype.forEach.call(node.childNodes, function (child) {
            if (child.nodeType === 1 && child.dataset && child.dataset.block === "image") {
                var src = child.dataset.src || "";
                if (!src) return;
                var block = { type: "image", src: src };
                var alt = child.querySelector(".blog-compose-alt");
                if (alt && alt.value.trim()) block.alt = alt.value.trim();
                // Width and height reserve the image's space on the public page. They come
                // from the library and are carried through rather than re-typed.
                if (child.dataset.w) block.w = Number(child.dataset.w);
                if (child.dataset.h) block.h = Number(child.dataset.h);
                out.push(block);
                return;
            }
            if (child.nodeType === 1 && child.querySelector('[data-block="image"]')) {
                collect(child, out);
                return;
            }
            var text = (child.textContent || "").replace(/\s+/g, " ").trim();
            if (text) out.push({ type: "text", text: text });
        });
    }

    function paragraphFor(text) {
        var p = document.createElement("p");
        p.textContent = text || "";
        // The <br> is what gives an empty paragraph a line for the caret to sit on, and it is
        // also what the placeholder rule in style.css keys off.
        if (!text) p.appendChild(document.createElement("br"));
        p.setAttribute("data-placeholder", el.body.getAttribute("data-placeholder") || "");
        return p;
    }

    /**
     * contenteditable="false" is what makes the image behave like one object: the caret
     * cannot get inside it, and Backspace beside it removes the whole figure instead of
     * eating it a character at a time.
     */
    function figureFor(block) {
        var figure = document.createElement("figure");
        figure.className = "blog-compose-figure";
        figure.contentEditable = "false";
        figure.dataset.block = "image";
        figure.dataset.src = block.src || "";
        if (block.w) figure.dataset.w = block.w;
        if (block.h) figure.dataset.h = block.h;

        var img = document.createElement("img");
        img.src = block.src || "";
        img.alt = block.alt || "";
        img.loading = "lazy";
        figure.appendChild(img);

        var bar = document.createElement("figcaption");
        bar.className = "blog-compose-bar";
        var alt = document.createElement("input");
        alt.type = "text";
        alt.className = "blog-compose-alt";
        alt.placeholder = "Mô tả ảnh (alt)";
        alt.value = block.alt || "";
        alt.addEventListener("input", function () { img.alt = alt.value; });
        bar.appendChild(alt);
        bar.appendChild(button("Xoá", function () {
            var next = figure.nextSibling;
            figure.remove();
            if (!el.body.firstChild) el.body.appendChild(paragraphFor(""));
            placeCaret(next);
        }, "danger-btn"));
        figure.appendChild(bar);
        return figure;
    }

    /* ---- caret ---- */

    /* Picking an image sends the reader to the media pane, which takes the focus and the
       selection with it. The last caret position inside the surface is remembered so the
       image lands where they were writing rather than at the end. */
    var savedRange = null;

    function rememberCaret() {
        var selection = window.getSelection();
        if (!selection.rangeCount) return;
        var range = selection.getRangeAt(0);
        if (el.body.contains(range.commonAncestorContainer)) savedRange = range.cloneRange();
    }

    function topLevelAt(node) {
        while (node && node.parentNode !== el.body) node = node.parentNode;
        return node;
    }

    function placeCaret(before) {
        var target = before && before.nodeType === 1 && before.dataset.block === "image"
            ? null
            : before;
        var p = target || paragraphFor("");
        if (!target) el.body.insertBefore(p, before);
        var range = document.createRange();
        range.selectNodeContents(p);
        range.collapse(true);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        el.body.focus();
    }

    function insertImage(media) {
        var figure = figureFor({
            src: media.deliveryUrl, alt: "", w: media.width, h: media.height,
        });
        var anchor = savedRange ? topLevelAt(savedRange.startContainer) : null;
        if (anchor && anchor.parentNode === el.body) {
            el.body.insertBefore(figure, anchor.nextSibling);
        } else {
            el.body.appendChild(figure);
        }
        // Somewhere to keep typing under the image, rather than a dead end.
        var after = paragraphFor("");
        el.body.insertBefore(after, figure.nextSibling);
        placeCaret(after);
        savedRange = null;
    }

    el.newBtn.addEventListener("click", function () { openEditor(null); });
    el.cancelBtn.addEventListener("click", closeEditor);
    el.editor.addEventListener("submit", save);
    el.addImage.addEventListener("click", function () {
        if (!window.CandleMedia) return;
        rememberCaret();
        window.CandleMedia.open(insertImage);
    });

    ["keyup", "mouseup", "blur"].forEach(function (type) {
        el.body.addEventListener(type, rememberCaret);
    });

    /* Paste as plain text. Pasted markup would be flattened by readBody anyway, so keeping it
       would only mean the surface showed something the saved post would not have. */
    el.body.addEventListener("paste", function (event) {
        event.preventDefault();
        var text = (event.clipboardData || window.clipboardData).getData("text/plain");
        document.execCommand("insertText", false, text);
    });

    /* admin.js decides whether this account is an admin; this only reacts to that. */
    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) refresh();
    });
})();
