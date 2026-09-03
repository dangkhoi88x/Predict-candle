/**
 * "Blog / Kiến Thức" tab — long-form trading-concept write-ups that don't reduce cleanly to a
 * detectable chart pattern (unlike BOS/SFP in technical-patterns.js, which came from the same
 * kind of source material but had a clean, mechanical definition worth turning into a real
 * pattern matcher). This tab is the lower-effort home for everything else: narrative reading
 * of market structure, order-flow reasoning, etc. Each entry is a from-scratch Vietnamese
 * summary (not a translation) of a public X post, with a link back to the original.
 *
 * Cards show only a cover illustration + title; clicking expands tags/body/source in place.
 * Covers are either a small hand-drawn SVG (self-made, themed to the post's concept) or an
 * author-provided image — the latter only for posts where the author gave explicit permission
 * to reuse their diagrams (see each post's `imageCredit`); those images were downloaded once
 * rather than hotlinked from the author, and each is individually credited.
 *
 * Those files now live in this project's own Cloudinary account under candles/blog/<author>/,
 * served through an f_auto,q_auto transform so each viewer gets WebP or AVIF at a width that
 * matches the slot rather than the 1536px original — around a third of the bytes.
 *
 * Cloudinary stores each upload unmodified and derives the transformed versions on request,
 * so the URLs below with the transform segment removed still return the byte-for-byte
 * originals. That is the only copy now: the local static/img/blog/ files were deleted once
 * that was verified for all 23.
 */
(function () {
    "use strict";

    var COVER_BOTTOM_FORMING =
        '<svg viewBox="0 0 400 150" preserveAspectRatio="none" xmlns="http://www.w3.org/2000/svg">' +
        '<rect x="0" y="0" width="400" height="150" fill="var(--panel-2)"/>' +
        '<line x1="20" y1="70" x2="380" y2="70" stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="5 4" opacity="0.6"/>' +
        '<text x="26" y="62" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--accent)">RECLAIM</text>' +
        '<path d="M30,40 L90,95 L140,118 L190,124 L230,110 L260,90 L300,72 L340,66 L370,60" ' +
        'fill="none" stroke="var(--muted)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" opacity="0.55"/>' +
        '<rect x="85" y="90" width="7" height="20" rx="1.5" fill="var(--down)"/>' +
        '<rect x="135" y="112" width="7" height="16" rx="1.5" fill="var(--down)"/>' +
        '<rect x="185" y="116" width="7" height="16" rx="1.5" fill="var(--up)"/>' +
        '<rect x="225" y="100" width="7" height="18" rx="1.5" fill="var(--down)"/>' +
        '<rect x="255" y="80" width="7" height="18" rx="1.5" fill="var(--up)"/>' +
        '<rect x="295" y="64" width="7" height="16" rx="1.5" fill="var(--up)"/>' +
        '<rect x="335" y="56" width="7" height="16" rx="1.5" fill="var(--up)"/>' +
        "</svg>";

    var COVER_LIQUIDITY_TRAP =
        '<svg viewBox="0 0 400 150" preserveAspectRatio="none" xmlns="http://www.w3.org/2000/svg">' +
        '<rect x="0" y="0" width="400" height="150" fill="var(--panel-2)"/>' +
        '<line x1="20" y1="55" x2="380" y2="55" stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="5 4" opacity="0.6"/>' +
        '<text x="26" y="47" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--accent)">RESISTANCE</text>' +
        '<path d="M30,95 L90,88 L140,92 L185,85" fill="none" stroke="var(--muted)" stroke-width="2" ' +
        'stroke-linecap="round" stroke-linejoin="round" opacity="0.55"/>' +
        '<line x1="205" y1="30" x2="205" y2="98" stroke="var(--up)" stroke-width="2"/>' +
        '<rect x="200" y="40" width="10" height="30" rx="1.5" fill="var(--up)"/>' +
        '<path d="M215,60 L260,70 L300,95 L340,112 L370,120" fill="none" stroke="var(--down)" stroke-width="2.5" ' +
        'stroke-linecap="round" stroke-linejoin="round"/>' +
        '<rect x="255" y="66" width="7" height="16" rx="1.5" fill="var(--down)"/>' +
        '<rect x="295" y="90" width="7" height="16" rx="1.5" fill="var(--down)"/>' +
        '<rect x="335" y="106" width="7" height="16" rx="1.5" fill="var(--down)"/>' +
        '<text x="205" y="22" text-anchor="middle" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--down)">SWEPT</text>' +
        "</svg>";

    var COVER_MARKET_STRUCTURE =
        '<svg viewBox="0 0 400 150" preserveAspectRatio="none" xmlns="http://www.w3.org/2000/svg">' +
        '<rect x="0" y="0" width="400" height="150" fill="var(--panel-2)"/>' +
        '<line x1="20" y1="95" x2="380" y2="95" stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="5 4" opacity="0.6"/>' +
        '<text x="26" y="87" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--accent)">HL</text>' +
        '<path d="M20,130 L60,90 L100,110 L140,70 L180,95 L220,80 L260,115 L300,100 L340,130 L380,140" ' +
        'fill="none" stroke="var(--muted)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" opacity="0.55"/>' +
        '<rect x="56" y="84" width="8" height="18" rx="1.5" fill="var(--up)"/>' +
        '<rect x="96" y="104" width="8" height="16" rx="1.5" fill="var(--down)"/>' +
        '<rect x="136" y="64" width="8" height="18" rx="1.5" fill="var(--up)"/>' +
        '<rect x="176" y="89" width="8" height="16" rx="1.5" fill="var(--down)"/>' +
        '<rect x="216" y="74" width="8" height="18" rx="1.5" fill="var(--up)"/>' +
        '<rect x="256" y="109" width="8" height="18" rx="1.5" fill="var(--down)"/>' +
        '<text x="140" y="55" text-anchor="middle" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--up)">HH</text>' +
        '<text x="220" y="70" text-anchor="middle" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--down)">LH</text>' +
        '<text x="264" y="132" text-anchor="middle" font-family="var(--mono)" font-size="10" font-weight="700" fill="var(--down)">BOS</text>' +
        "</svg>";

    function buildPost(post) {
        var article = document.createElement("div");
        article.className = "blog-post";

        var cover = document.createElement("div");
        cover.className = "blog-post-cover";
        if (post.coverImg) {
            var coverImg = document.createElement("img");
            coverImg.src = post.coverImg;
            coverImg.alt = post.title;
            coverImg.loading = "lazy";
            cover.appendChild(coverImg);
        } else {
            cover.innerHTML = post.cover;
        }

        var summary = document.createElement("button");
        summary.className = "blog-post-summary";
        summary.type = "button";

        var title = document.createElement("h3");
        title.className = "blog-post-title";
        title.textContent = post.title;

        var hint = document.createElement("span");
        hint.className = "blog-post-hint";
        hint.textContent = "Bấm để xem chi tiết ▾";

        summary.appendChild(title);
        summary.appendChild(hint);

        var detail = document.createElement("div");
        detail.className = "blog-post-detail hidden";

        var tagsRow = document.createElement("div");
        tagsRow.className = "blog-post-tags";
        post.tags.forEach(function (tag) {
            var pill = document.createElement("span");
            pill.className = "blog-tag";
            pill.textContent = tag;
            tagsRow.appendChild(pill);
        });

        var body = document.createElement("div");
        body.className = "blog-post-body";
        if (post.content) {
            /* blog-render.js draws both shapes the column can hold — a ProseMirror document
               from the Tiptap editor, and the older flat block array — and hands back a
               fragment rather than a string, so nothing here can reach for innerHTML. The
               intrinsic width and height still travel with each image, which is what reserves
               its box before the file lands; these vary per image (3:2, 16:9, 1.91:1), so one
               CSS aspect-ratio for the lot would reserve the wrong height for most of them. */
            body.appendChild(window.CandleBlogRender.toFragment(post.content));
        } else {
            post.paragraphs.forEach(function (text) {
                var p = document.createElement("p");
                p.textContent = text;
                body.appendChild(p);
            });
        }

        var source = document.createElement("a");
        source.className = "blog-post-source";
        source.href = post.sourceUrl;
        source.target = "_blank";
        source.rel = "noopener";
        source.textContent = "Đọc bài gốc: " + post.source + " ↗";

        detail.appendChild(tagsRow);
        detail.appendChild(body);
        if (post.imageCredit) {
            var credit = document.createElement("p");
            credit.className = "blog-post-credit";
            credit.textContent = post.imageCredit;
            detail.appendChild(credit);
        }
        detail.appendChild(source);

        summary.addEventListener("click", function () {
            var expanded = !detail.classList.contains("hidden");
            detail.classList.toggle("hidden", expanded);
            hint.textContent = expanded ? "Bấm để xem chi tiết ▾" : "Ẩn bớt ▴";
            article.classList.toggle("expanded", !expanded);
        });

        article.appendChild(cover);
        article.appendChild(summary);
        article.appendChild(detail);
        return article;
    }

    /* Deferred until the tab is first shown. loading="lazy" does not help here: it defers
       based on where an image sits relative to the viewport, and an image inside a
       display:none view has no box to position, so the browser fetches it immediately —
       2.1 MB of screenshots on first paint for a tab most visitors never open. */
    var built = false;

    /*
     * Posts come from /api/blog/posts and nowhere else now. The seeded array this renderer was
     * originally written against is gone, so this only renames the two fields the API spells
     * differently — coverSvg and body, where the renderer says cover and content.
     */
    function fromApi(post) {
        return {
            title: post.title,
            cover: post.coverSvg,
            coverImg: post.coverImg,
            tags: post.tags || [],
            source: post.source,
            sourceUrl: post.sourceUrl,
            content: post.body || [],
            imageCredit: post.imageCredit,
        };
    }

    function render(posts) {
        var list = document.getElementById("blog-list");
        list.innerHTML = "";
        posts.forEach(function (post) {
            list.appendChild(buildPost(post));
        });
    }

    async function init() {
        if (built) return;
        built = true;
        var list = document.getElementById("blog-list");

        try {
            var res = await fetch("/api/blog/posts");
            if (!res.ok) throw new Error("Máy chủ trả về " + res.status);
            var posts = await res.json();
            if (!posts.length) {
                window.CandleContent.notice(list, "Chưa có bài viết nào.");
                return;
            }
            render(posts.map(fromApi));
        } catch (e) {
            /* There is no compiled-in copy behind this any more, so a failure is the whole
               tab. Clearing `built` matters as much as the message: this runs once on first
               reveal, and without it a single dropped request would leave the tab empty for
               the rest of the visit with no way to ask again. */
            built = false;
            window.CandleContent.notice(list, "Không tải được bài viết. Mở lại tab này để thử lần nữa.");
        }
    }

    window.__initBlogView = init;
})();
