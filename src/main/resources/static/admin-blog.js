/**
 * Blog CRUD for the admin page: the list of posts, and the editor behind it.
 *
 * The body is written in Tiptap and stored as the ProseMirror document it produces. This file
 * holds only the handle that `web/src/blog-editor.js` hands back — it never imports Tiptap and
 * never touches a ProseMirror object beyond the plain JSON going into the `body` column, so
 * the editor bundle stays a detail of the admin page.
 *
 * The public blog does **not** load that bundle. `blog-render.js` draws the same documents
 * with its own walker, which is what keeps ~395 KB of editor off a page whose weight this
 * project spent a release cutting. The cost is that both ends have to know the same node
 * types: adding a Tiptap extension means adding a branch in blog-render.js, and that file
 * warns rather than silently dropping anything it has not been taught.
 *
 * Both body shapes are read. Until V12 has run against a given database the column may still
 * hold the older flat block array, and opening one of those must not present an empty editor
 * that then saves over the post.
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
        linkBtn: document.getElementById("blog-link"),
        toolbar: document.getElementById("blog-toolbar"),
        count: document.getElementById("blog-count"),
        body: document.getElementById("blog-body"),
        status: document.getElementById("admin-status"),
        f: {
            title: document.getElementById("f-title"),
            slug: document.getElementById("f-slug"),
            tags: document.getElementById("f-tags"),
            position: document.getElementById("f-position"),
            coverImg: document.getElementById("f-cover-img"),
            coverPreview: document.getElementById("f-cover-preview"),
            coverEmpty: document.getElementById("f-cover-empty"),
            coverUpload: document.getElementById("f-cover-upload"),
            coverPick: document.getElementById("f-cover-pick"),
            coverClear: document.getElementById("f-cover-clear"),
            coverFile: document.getElementById("f-cover-file"),
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
        return tags + " · " + state + " · " + blockCount(post.body) + " khối";
    }

    /** A document counts its top-level nodes; the older array counts itself. */
    function blockCount(body) {
        if (Array.isArray(body)) return body.length;
        if (body && body.type === "doc") return (body.content || []).length;
        return 0;
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
            /* No longer editable here, but PUT replaces the whole post and BlogService.apply
               writes every field it is handed — so these travel back exactly as they came.
               Sending null instead would silently erase the attribution the seeded posts
               carry, and the SVG that is the only cover the first one has. */
            source: (editing && editing.source) || null,
            sourceUrl: (editing && editing.sourceUrl) || null,
            imageCredit: (editing && editing.imageCredit) || null,
            coverSvg: (editing && editing.coverSvg) || null,
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
        el.f.coverImg.value = (post && post.coverImg) || "";
        paintCover();
        el.f.published.checked = !!(post && post.published);
        el.editor.classList.remove("hidden");
        el.f.title.focus();
    }

    function closeEditor() {
        editing = null;
        /* Clear through the editor, never by emptying #blog-body: Tiptap owns that element,
           and wiping its innerHTML tears out the mount while leaving `composer` pointing at
           the wreckage — the next post then opens into an editor with no DOM. */
        if (composer) composer.setContent(emptyDoc());
        el.editor.classList.add("hidden");
        setStatus("");
    }

    /* ---- the writing surface ----------------------------------------------------------
       Tiptap owns everything inside #blog-body and is bundled from web/src/blog-editor.js.
       This module only holds the handle: it never imports Tiptap, never sees a ProseMirror
       object beyond the plain JSON that goes in the `body` column, and would degrade to a
       read-only editor rather than break if the bundle failed to load. */

    var composer = null;

    function ensureComposer() {
        if (composer || !window.CandleEditor) return composer;
        composer = window.CandleEditor.mount(el.body, {
            placeholder: "Viết ở đây. Dán hoặc kéo ảnh vào thẳng khung này.",
            onStatus: setStatus,
            onUpdate: paintToolbar,
            /* Paste and drop upload through the endpoint the library already uses, and the
               response now carries width and height — which is what lets a pasted image
               reserve its box on the public page the same way a picked one does. */
            upload: uploadImage,
        });
        composer.onSelection(paintToolbar);
        return composer;
    }

    function mediaFolder() {
        var input = document.getElementById("media-folder");
        return (input && input.value.trim()) || "candles/blog";
    }

    /** An empty document still needs one paragraph, or there is nowhere to put the caret. */
    function emptyDoc() {
        return { type: "doc", content: [{ type: "paragraph" }] };
    }

    /**
     * The column holds either a ProseMirror document or, until V12 has run against whatever
     * database this is pointed at, the older flat block array. Reading both here means an
     * un-migrated post opens in the editor instead of appearing empty and being saved over.
     */
    function toDoc(body) {
        if (body && body.type === "doc") return body;
        if (!Array.isArray(body) || !body.length) return emptyDoc();
        return {
            type: "doc",
            content: body.map(function (block) {
                if (block.type === "image") {
                    return { type: "image", attrs: {
                        src: block.src, alt: block.alt || "",
                        width: block.w || null, height: block.h || null,
                    } };
                }
                return block.text
                    ? { type: "paragraph", content: [{ type: "text", text: block.text }] }
                    : { type: "paragraph" };
            }),
        };
    }

    function writeBody(body) {
        var editor = ensureComposer();
        if (editor) editor.setContent(toDoc(body));
        paintToolbar();
    }

    function readBody() {
        return composer ? composer.json() : emptyDoc();
    }

    /* ---- cover image ---- */

    /* One upload path for the whole page: the same endpoint the library and the editor's
       paste handler use, so a cover lands in the same Cloudinary folder as everything else
       and is deletable from the library like any other image. */
    async function uploadImage(file) {
        var form = new FormData();
        form.append("file", file);
        var res = await window.CandleAuth.authFetch(
            "/api/media/images?folder=" + encodeURIComponent(mediaFolder()),
            { method: "POST", body: form });
        var payload = await res.json();
        if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
        return payload;
    }

    function paintCover(failed) {
        var url = el.f.coverImg.value.trim();
        var show = !!url && !failed;
        el.f.coverPreview.classList.toggle("hidden", !show);
        el.f.coverEmpty.classList.toggle("hidden", show);
        el.f.coverClear.classList.toggle("hidden", !url);
        // A URL that does not resolve is worth saying out loud — a broken-image glyph looks
        // like the page is broken rather than the address being wrong.
        el.f.coverEmpty.textContent = failed ? "Không tải được ảnh" : "Chưa có ảnh";
        // Only touch src when it changes: reassigning the same URL restarts the fetch and
        // flashes the preview on every keystroke in the URL field.
        if (url && el.f.coverPreview.getAttribute("src") !== url) {
            el.f.coverPreview.src = url;
        }
        if (!url) el.f.coverPreview.removeAttribute("src");
    }

    function setCover(url) {
        el.f.coverImg.value = url || "";
        paintCover();
    }

    /* ---- toolbar ---- */

    function paintToolbar() {
        if (!composer) return;
        Array.prototype.forEach.call(el.toolbar.querySelectorAll("[data-active]"), function (btn) {
            var spec = btn.dataset.active.split(":");
            var on = spec.length > 1
                ? composer.isActive(spec[0], { level: Number(spec[1]) })
                : composer.isActive(spec[0]);
            btn.classList.toggle("is-on", on);
            btn.setAttribute("aria-pressed", on ? "true" : "false");
        });
        el.count.textContent = composer.words() + " từ · " + composer.characters() + " ký tự";
    }

    el.newBtn.addEventListener("click", function () { openEditor(null); });
    el.cancelBtn.addEventListener("click", closeEditor);
    el.editor.addEventListener("submit", save);
    el.toolbar.addEventListener("click", function (event) {
        var btn = event.target.closest("[data-cmd]");
        if (!btn || !composer) return;
        composer.run(btn.dataset.cmd);
        paintToolbar();
    });

    el.f.coverUpload.addEventListener("click", function () { el.f.coverFile.click(); });

    el.f.coverFile.addEventListener("change", async function () {
        var file = el.f.coverFile.files[0];
        el.f.coverFile.value = "";          // so re-picking the same file fires change again
        if (!file) return;
        setStatus("Đang tải ảnh bìa lên…");
        try {
            var media = await uploadImage(file);
            setCover(media.url);
            setStatus("Đã tải ảnh bìa lên.");
        } catch (e) {
            setStatus("Không tải được ảnh bìa: " + e.message);
        }
    });

    el.f.coverPick.addEventListener("click", function () {
        if (!window.CandleMedia) return;
        window.CandleMedia.open(function (media) { setCover(media.deliveryUrl); });
    });

    el.f.coverClear.addEventListener("click", function () { setCover(""); });

    el.f.coverImg.addEventListener("input", function () { paintCover(false); });
    el.f.coverPreview.addEventListener("error", function () {
        if (el.f.coverImg.value.trim()) paintCover(true);
    });
    el.f.coverPreview.addEventListener("load", function () { paintCover(false); });

    el.addImage.addEventListener("click", function () {
        if (!window.CandleMedia || !composer) return;
        window.CandleMedia.open(function (media) {
            composer.insertImage(media);
        });
    });

    el.linkBtn.addEventListener("click", function () {
        if (!composer) return;
        var href = window.prompt("Địa chỉ liên kết (http hoặc https):", "https://");
        if (href === null) return;
        if (!href.trim()) {
            composer.unsetLink();
            return;
        }
        // The editor refuses anything but http/https, and blog-render.js checks again when it
        // draws — the editor is a convenience, not the boundary.
        if (!composer.setLink(href)) setStatus("Chỉ nhận liên kết http hoặc https.");
    });

    /* admin.js decides whether this account is an admin; this only reacts to that. */
    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) refresh();
    });
})();
