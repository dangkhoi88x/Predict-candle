/**
 * Draws a blog post body on the public page.
 *
 * The admin writes posts in Tiptap and stores ordinary ProseMirror JSON. Rendering it here
 * with Tiptap's own extensions would mean every reader downloading ~395 KB of editor to look
 * at three articles, on the page whose weight this project spent a release cutting. So this
 * walks the same document with `createElement` instead: about five kilobytes, no ProseMirror,
 * and no `innerHTML` anywhere in it.
 *
 * The cost of that trade is a coupling worth naming: **every node or mark type the editor can
 * produce needs a branch here.** Adding a Tiptap extension without adding one leaves the
 * public page unable to draw what an admin just published. Unknown types therefore fall back
 * to their text and warn, rather than vanishing quietly.
 *
 * It also still reads the older format — a flat array of {type:"text"|"image"} blocks — so
 * posts written before the editor changed keep rendering, converted or not.
 */
(function () {
    "use strict";

    var SAFE_PROTOCOL = /^https?:\/\//i;

    /** An href is the most direct route from a stored document to script on a public page. */
    function safeHref(href) {
        return typeof href === "string" && SAFE_PROTOCOL.test(href.trim()) ? href.trim() : null;
    }

    var MARK_TAGS = {
        bold: "strong",
        strong: "strong",
        italic: "em",
        em: "em",
        underline: "u",
        strike: "s",
        code: "code",
    };

    function warn(what, value) {
        if (window.console && console.warn) {
            console.warn("[blog-render] no branch for " + what + ' "' + value
                + '" — teach blog-render.js about it, the editor can produce it.');
        }
    }

    /** Wraps a text node in one element per mark, innermost first. */
    function withMarks(text, marks) {
        var node = document.createTextNode(text || "");
        (marks || []).forEach(function (mark) {
            var wrapper;
            if (mark.type === "link") {
                var href = safeHref(mark.attrs && mark.attrs.href);
                if (!href) {
                    // A link we will not follow is still text worth reading.
                    return;
                }
                wrapper = document.createElement("a");
                wrapper.href = href;
                wrapper.target = "_blank";
                wrapper.rel = "noopener noreferrer";
            } else if (MARK_TAGS[mark.type]) {
                wrapper = document.createElement(MARK_TAGS[mark.type]);
            } else {
                warn("mark", mark.type);
                return;
            }
            wrapper.appendChild(node);
            node = wrapper;
        });
        return node;
    }

    function align(el, attrs) {
        if (attrs && attrs.textAlign && attrs.textAlign !== "left") {
            el.style.textAlign = attrs.textAlign;
        }
        return el;
    }

    function children(node, into) {
        (node.content || []).forEach(function (child) {
            var built = build(child);
            if (built) into.appendChild(built);
        });
        return into;
    }

    function image(attrs) {
        var src = attrs && attrs.src;
        if (!src) return null;
        var figure = document.createElement("figure");
        figure.className = "blog-post-figure";
        var img = document.createElement("img");
        img.src = src;
        img.alt = (attrs && attrs.alt) || "";
        img.loading = "lazy";
        img.decoding = "async";
        /* Width and height are what let the browser reserve the image's box before it
           arrives. Without them every image on the page shoves the text below it downward as
           it loads, which is the layout shift the editor's SizedImage node exists to prevent. */
        if (attrs.width) img.width = Number(attrs.width) || undefined;
        if (attrs.height) img.height = Number(attrs.height) || undefined;
        figure.appendChild(img);
        return figure;
    }

    function build(node) {
        if (!node || !node.type) return null;
        switch (node.type) {
            case "text":
                return withMarks(node.text, node.marks);
            case "paragraph":
                return children(node, align(document.createElement("p"), node.attrs));
            case "heading":
                var level = (node.attrs && node.attrs.level) || 2;
                // h1 belongs to the post title; a body heading starts at h2 so the document
                // outline stays in order.
                var h = document.createElement("h" + Math.min(Math.max(level, 2), 4));
                return children(node, align(h, node.attrs));
            case "bulletList":
                return children(node, document.createElement("ul"));
            case "orderedList":
                return children(node, document.createElement("ol"));
            case "listItem":
                return children(node, document.createElement("li"));
            case "blockquote":
                return children(node, document.createElement("blockquote"));
            case "codeBlock":
                var pre = document.createElement("pre");
                var code = document.createElement("code");
                children(node, code);
                pre.appendChild(code);
                return pre;
            case "horizontalRule":
                return document.createElement("hr");
            case "hardBreak":
                return document.createElement("br");
            case "image":
                return image(node.attrs || {});
            default:
                warn("node", node.type);
                // Whatever it was, it had text in it; show that rather than nothing.
                var fallback = document.createElement("p");
                fallback.textContent = plainText(node);
                return fallback.textContent ? fallback : null;
        }
    }

    function plainText(node) {
        if (!node) return "";
        if (node.type === "text") return node.text || "";
        return (node.content || []).map(plainText).join("");
    }

    /** The pre-Tiptap shape: a flat array of text and image blocks. */
    function legacy(blocks) {
        var fragment = document.createDocumentFragment();
        blocks.forEach(function (block) {
            if (block.type === "image") {
                var figure = image({ src: block.src, alt: block.alt, width: block.w, height: block.h });
                if (figure) fragment.appendChild(figure);
                return;
            }
            if (!block.text) return;
            var p = document.createElement("p");
            p.textContent = block.text;
            fragment.appendChild(p);
        });
        return fragment;
    }

    /**
     * `body` is whatever the column holds: a ProseMirror doc, or the older block array.
     * Returns a fragment ready to append — never a string, so no caller can be tempted into
     * innerHTML.
     */
    function toFragment(body) {
        if (Array.isArray(body)) return legacy(body);
        if (!body || body.type !== "doc") return document.createDocumentFragment();
        return children(body, document.createDocumentFragment());
    }

    window.CandleBlogRender = { toFragment: toFragment, safeHref: safeHref };
})();
