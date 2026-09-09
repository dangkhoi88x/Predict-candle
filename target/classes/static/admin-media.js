/**
 * The media library: upload, browse, copy a URL, delete.
 *
 * Also the picker the blog editor uses. Those are the same list, so they are the same code —
 * the only difference is that picking closes a dialog and hands the image back, which is what
 * `open(onPick)` is for. A separate picker would have been a second place to fix every time
 * the listing changed.
 */
(function () {
    "use strict";

    var FOLDER_KEY = "candles.admin.mediaFolder";

    var el = {
        section: document.getElementById("admin-media"),
        folder: document.getElementById("media-folder"),
        reload: document.getElementById("media-reload"),
        drop: document.getElementById("media-drop"),
        file: document.getElementById("media-file"),
        grid: document.getElementById("media-grid"),
        empty: document.getElementById("media-empty"),
        more: document.getElementById("media-more"),
        status: document.getElementById("admin-status"),
    };

    var cursor = null;
    var loaded = [];
    var pickHandler = null;   // set while the picker dialog is open

    function setStatus(text) {
        el.status.textContent = text || "";
    }

    function formatSize(bytes) {
        return bytes >= 1048576
            ? (bytes / 1048576).toFixed(1) + " MB"
            : Math.round(bytes / 1024) + " KB";
    }

    try {
        el.folder.value = localStorage.getItem(FOLDER_KEY) || el.folder.value;
    } catch (e) {
        // Storage blocked — the default folder is fine.
    }

    /* ---- listing ---- */

    async function load(append) {
        var folder = el.folder.value.trim() || "candles/blog";
        setStatus("Đang tải thư viện…");
        try {
            var url = "/api/media/images?folder=" + encodeURIComponent(folder)
                + (append && cursor ? "&cursor=" + encodeURIComponent(cursor) : "");
            var res = await window.CandleAuth.authFetch(url);
            var payload = await res.json();
            if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));

            cursor = payload.nextCursor || null;
            loaded = append ? loaded.concat(payload.items) : payload.items;
            render();
            setStatus("");
        } catch (e) {
            setStatus("Không tải được thư viện: " + e.message);
        }
    }

    function render() {
        el.grid.innerHTML = "";
        el.empty.classList.toggle("hidden", loaded.length > 0);
        el.more.classList.toggle("hidden", !cursor);
        loaded.forEach(function (item) { el.grid.appendChild(tile(item)); });
    }

    function tile(item) {
        var card = document.createElement("figure");
        card.className = "media-tile";

        var img = document.createElement("img");
        img.src = item.thumbUrl;
        img.alt = "";
        img.loading = "lazy";
        card.appendChild(img);

        var caption = document.createElement("figcaption");
        var name = document.createElement("span");
        name.className = "media-name";
        // Only the last segment: the folder is the same for every tile on screen.
        name.textContent = item.publicId.split("/").pop();
        name.title = item.publicId;
        var meta = document.createElement("span");
        meta.className = "media-meta";
        meta.textContent = item.width + "×" + item.height + " · " + formatSize(item.bytes);
        caption.appendChild(name);
        caption.appendChild(meta);
        card.appendChild(caption);

        var actions = document.createElement("div");
        actions.className = "media-actions";
        if (pickHandler) {
            actions.appendChild(button("Chọn", function () {
                var handler = pickHandler;
                closePicker();
                handler(item);
            }, "primary-btn"));
        } else {
            actions.appendChild(button("Copy URL", function (event) {
                copy(item.deliveryUrl, event.target);
            }));
            actions.appendChild(button("Xoá", function () { remove(item); }, "danger-btn"));
        }
        card.appendChild(actions);
        return card;
    }

    function button(label, onClick, cls) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = cls || "ghost-btn";
        b.textContent = label;
        b.addEventListener("click", onClick);
        return b;
    }

    async function copy(text, target) {
        try {
            await navigator.clipboard.writeText(text);
            var previous = target.textContent;
            target.textContent = "Đã copy";
            setTimeout(function () { target.textContent = previous; }, 1200);
        } catch (e) {
            // Clipboard refused (insecure context, permission): show it so it can be copied by hand.
            window.prompt("Copy URL:", text);
        }
    }

    /* ---- write ---- */

    async function upload(files) {
        var folder = el.folder.value.trim() || "candles/blog";
        for (var i = 0; i < files.length; i++) {
            setStatus("Đang tải lên " + files[i].name + " (" + (i + 1) + "/" + files.length + ")…");
            var form = new FormData();
            form.append("file", files[i]);
            try {
                var res = await window.CandleAuth.authFetch(
                    "/api/media/images?folder=" + encodeURIComponent(folder),
                    { method: "POST", body: form });
                var payload = await res.json();
                if (!res.ok) throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            } catch (e) {
                setStatus("Tải lên thất bại: " + e.message);
                return;
            }
        }
        cursor = null;
        await load(false);
        setStatus("Đã tải lên " + files.length + " ảnh.");
    }

    async function remove(item) {
        // Cloudinary deletion is permanent, and a post still pointing at the image will show a
        // hole. Naming the file in the prompt is the least a confirm dialog can do.
        if (!window.confirm("Xoá vĩnh viễn " + item.publicId + "?\n\nBài viết đang dùng ảnh này sẽ mất ảnh.")) {
            return;
        }
        setStatus("Đang xoá…");
        try {
            var res = await window.CandleAuth.authFetch(
                "/api/media/images?publicId=" + encodeURIComponent(item.publicId), { method: "DELETE" });
            if (!res.ok) {
                var payload = await res.json();
                throw new Error(payload.message || ("Máy chủ trả về " + res.status));
            }
            loaded = loaded.filter(function (other) { return other.publicId !== item.publicId; });
            render();
            setStatus("Đã xoá.");
        } catch (e) {
            setStatus("Xoá thất bại: " + e.message);
        }
    }

    /* ---- picker ---- */

    /* The library is its own pane now, so the blog editor asking for a picker has to be
       taken there and brought back — scrolling to a section the shell is not showing would
       land on nothing. Which pane to return to is remembered rather than assumed: the
       content editor may want the picker one day too. */
    var returnPane = null;

    function openPicker(onPick) {
        pickHandler = onPick;
        el.section.classList.add("is-picking");
        if (window.CandleAdminNav) {
            returnPane = window.CandleAdminNav.current();
            window.CandleAdminNav.go("media");
        } else {
            el.section.scrollIntoView({ block: "start", behavior: "smooth" });
        }
        render();
        setStatus("Chọn một ảnh để chèn vào bài viết.");
    }

    function closePicker() {
        pickHandler = null;
        el.section.classList.remove("is-picking");
        if (returnPane && window.CandleAdminNav) window.CandleAdminNav.go(returnPane);
        returnPane = null;
        render();
        setStatus("");
    }

    /* ---- wiring ---- */

    el.reload.addEventListener("click", function () { cursor = null; load(false); });
    el.more.addEventListener("click", function () { load(true); });
    el.folder.addEventListener("change", function () {
        try {
            localStorage.setItem(FOLDER_KEY, el.folder.value.trim());
        } catch (e) {
            // Not worth failing a reload over.
        }
        cursor = null;
        load(false);
    });

    el.file.addEventListener("change", function () {
        if (el.file.files.length) upload(el.file.files);
        el.file.value = "";
    });

    ["dragenter", "dragover"].forEach(function (type) {
        el.drop.addEventListener(type, function (event) {
            event.preventDefault();
            el.drop.classList.add("is-over");
        });
    });
    ["dragleave", "drop"].forEach(function (type) {
        el.drop.addEventListener(type, function (event) {
            event.preventDefault();
            el.drop.classList.remove("is-over");
        });
    });
    el.drop.addEventListener("drop", function (event) {
        var files = event.dataTransfer && event.dataTransfer.files;
        if (files && files.length) upload(files);
    });

    document.addEventListener("candles:admin", function (event) {
        el.section.classList.toggle("hidden", !event.detail.admin);
        if (event.detail.admin) load(false);
    });

    /** The blog editor's way in. onPick receives the chosen StoredMedia. */
    window.CandleMedia = { open: openPicker, close: closePicker };
})();
