/**
 * Topbar search: find a row in any pane without knowing which pane it lives in.
 *
 * It reads the rendered DOM rather than the modules' data. That sounds indirect but is the
 * one place this shell makes it the honest choice: every pane is built and sitting in the
 * document at once — only CSS hides the six you are not looking at — so the rows are all
 * right there, and searching them costs nothing and needs no module to expose its state.
 *
 * What that buys, and what it costs: the index is exactly what has loaded. The content pane
 * holds one kind at a time, and the image library holds the pages fetched so far, so those
 * are searched as far as they have been read and no further. The empty state says so rather
 * than implying the thing you wanted is not there.
 */
(function () {
    "use strict";

    var MIN_QUERY = 2;
    var MAX_RESULTS = 8;
    var HIGHLIGHT_MS = 1600;

    /* Where the rows are, and what to call the pane they are in. Adding a pane here is the
       whole job of teaching search about it. */
    var SOURCES = [
        { pane: "ops", label: "Vận hành", rows: "#ops-assets tbody tr", primary: "td" },
        { pane: "assets", label: "Cặp giao dịch", rows: "#asset-table tbody tr", primary: "td" },
        { pane: "players", label: "Người chơi", rows: "#player-table tbody tr", primary: "td:nth-child(2)" },
        { pane: "content", label: "Thư viện nội dung", rows: "#content-list .blog-admin-row", primary: ".blog-admin-title" },
        { pane: "blog", label: "Bài viết", rows: "#blog-list-admin .blog-admin-row", primary: ".blog-admin-title" },
        { pane: "media", label: "Thư viện ảnh", rows: "#media-grid .media-tile", primary: ".media-name" },
    ];

    var box = document.querySelector(".adm-search");
    var input = document.getElementById("adm-search-input");
    var panel = document.getElementById("adm-search-results");
    if (!box || !input || !panel) return;

    var results = [];
    var active = -1;

    /**
     * Vietnamese without the diacritics, so "van hanh" finds "Vận hành" and "cau truc" finds
     * "Cấu trúc". Anyone typing quickly into a search box is not reaching for tone marks.
     *
     * Separators collapse to spaces for the same reason: half the searchable text here is
     * slugs and Cloudinary ids — `cau-truc-thi-truong`, `bullish-engulfing` — and nobody
     * types the hyphens when they are looking for the post they wrote.
     */
    function fold(text) {
        return (text || "")
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")   // the combining marks NFD just split off
            .replace(/đ/g, "d").replace(/Đ/g, "D")
            .toLowerCase()
            .replace(/[-_/.]+/g, " ")
            .replace(/\s+/g, " ")
            .trim();
    }

    /** Row text plus every title attribute in it — that is where the full wallet address and
        the full Cloudinary id live, while the cell itself shows an abbreviation. */
    function haystack(row) {
        var text = row.textContent || "";
        Array.prototype.forEach.call(row.querySelectorAll("[title]"), function (el) {
            text += " " + el.getAttribute("title");
        });
        if (row.getAttribute("title")) text += " " + row.getAttribute("title");
        return fold(text);
    }

    function firstText(row, selector) {
        var el = row.querySelector(selector);
        var text = el ? el.textContent.trim() : row.textContent.trim();
        return text || "—";
    }

    /** A short second line: what the row says apart from its own name. */
    function detail(row, source) {
        if (source.pane === "content" || source.pane === "blog") {
            var meta = row.querySelector(".blog-admin-meta");
            return meta ? meta.textContent.trim() : "";
        }
        if (source.pane === "media") {
            var name = row.querySelector(".media-name");
            return name && name.getAttribute("title") ? name.getAttribute("title") : "";
        }
        var cells = row.querySelectorAll("td");
        if (!cells.length) return "";
        var parts = [];
        for (var i = 0; i < cells.length && parts.length < 3; i++) {
            var value = cells[i].textContent.trim();
            if (value && !/^[↑↓]$/.test(value)) parts.push(value);
        }
        return parts.join(" · ");
    }

    function search(query) {
        var needle = fold(query.trim());
        var found = [];
        SOURCES.forEach(function (source) {
            var rows = document.querySelectorAll(source.rows);
            Array.prototype.forEach.call(rows, function (row) {
                if (found.length >= MAX_RESULTS * 3) return;
                if (haystack(row).indexOf(needle) === -1) return;
                found.push({
                    row: row,
                    pane: source.pane,
                    paneLabel: source.label,
                    title: firstText(row, source.primary),
                    detail: detail(row, source),
                });
            });
        });
        return found.slice(0, MAX_RESULTS);
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function render() {
        panel.innerHTML = "";
        if (!results.length) {
            var empty = element("p", "adm-search-empty");
            empty.appendChild(document.createTextNode("Không tìm thấy. "));
            // Say what was searched, so an absent row reads as "not loaded" and not as "gone".
            empty.appendChild(element("span", "adm-search-scope",
                "Chỉ tìm trong dữ liệu đã tải — thư viện nội dung chỉ giữ loại đang chọn."));
            panel.appendChild(empty);
            return;
        }

        var lastPane = null;
        results.forEach(function (hit, index) {
            if (hit.paneLabel !== lastPane) {
                panel.appendChild(element("p", "adm-search-group", hit.paneLabel));
                lastPane = hit.paneLabel;
            }
            var item = element("button", "adm-search-item");
            item.type = "button";
            item.setAttribute("role", "option");
            item.setAttribute("aria-selected", index === active ? "true" : "false");
            if (index === active) item.classList.add("is-active");
            item.appendChild(element("span", "adm-search-title", hit.title));
            if (hit.detail) item.appendChild(element("span", "adm-search-detail", hit.detail));
            item.addEventListener("mousedown", function (event) {
                // mousedown, not click: blur would close the panel before click landed.
                event.preventDefault();
                open(index);
            });
            panel.appendChild(item);
        });
    }

    function show() {
        panel.classList.remove("hidden");
        box.setAttribute("aria-expanded", "true");
    }

    function close() {
        panel.classList.add("hidden");
        box.setAttribute("aria-expanded", "false");
        active = -1;
    }

    var highlightTimer = null;

    function open(index) {
        var hit = results[index];
        if (!hit) return;
        if (window.CandleAdminNav) window.CandleAdminNav.go(hit.pane);
        close();
        input.blur();

        // The pane has to be displayed before scrollIntoView can measure anything in it.
        requestAnimationFrame(function () {
            /* Smooth scrolling is written here in JS, which is exactly the case the
               reduced-motion block in style.css cannot reach — it can only collapse the
               duration tokens. So this asks the preference itself. */
            var still = window.matchMedia
                && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
            hit.row.scrollIntoView({ block: "center", behavior: still ? "auto" : "smooth" });
            clearTimeout(highlightTimer);
            Array.prototype.forEach.call(document.querySelectorAll(".is-found"), function (el) {
                el.classList.remove("is-found");
            });
            hit.row.classList.add("is-found");
            highlightTimer = setTimeout(function () {
                hit.row.classList.remove("is-found");
            }, HIGHLIGHT_MS);
        });
    }

    function update() {
        var query = input.value.trim();
        if (query.length < MIN_QUERY) {
            results = [];
            close();
            return;
        }
        results = search(query);
        active = results.length ? 0 : -1;
        render();
        show();
    }

    input.addEventListener("input", update);

    input.addEventListener("focus", function () {
        if (input.value.trim().length >= MIN_QUERY) update();
    });

    input.addEventListener("keydown", function (event) {
        if (event.key === "Escape") {
            input.value = "";
            close();
            return;
        }
        if (!results.length || panel.classList.contains("hidden")) return;
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            var step = event.key === "ArrowDown" ? 1 : -1;
            active = (active + step + results.length) % results.length;
            render();
        } else if (event.key === "Enter") {
            event.preventDefault();
            open(active);
        }
    });

    document.addEventListener("click", function (event) {
        if (!box.contains(event.target)) close();
    });

    /* A pane's rows are replaced wholesale on every refresh, so a result still on screen may
       be pointing at a detached node. Re-running the query is cheaper than tracking that. */
    document.addEventListener("candles:ops", function () {
        if (!panel.classList.contains("hidden")) update();
    });
})();
