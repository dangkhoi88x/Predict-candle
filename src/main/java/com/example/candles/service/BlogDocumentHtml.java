package com.example.candles.service;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A stored blog document, as HTML, for the server-rendered page a search engine reads.
 *
 * <b>This is the third renderer of one document, and that is a real cost worth naming.</b> The
 * editor (Tiptap) produces it, {@code blog-render.js} draws it in the reader's browser, and this
 * writes it for a crawler that runs no JavaScript. A node the editor can emit needs a branch in
 * all three, and the two public ones are kept deliberately identical in what they accept: the
 * same node names, the same six marks, the same http(s)-only link check, and the same fallback —
 * an unknown node becomes its own text rather than vanishing.
 *
 * The alternative was storing rendered HTML beside the document at publish time, which is a second
 * copy free to drift the moment either renderer is improved. A pure function over the document
 * cannot drift; it can only be incomplete, and being incomplete is visible.
 *
 * Every piece of text is escaped here, including attributes: this builds a string rather than DOM
 * nodes, so nothing else is doing it.
 */
public final class BlogDocumentHtml {

    private static final Map<String, String> MARK_TAGS = Map.of(
            "bold", "strong", "strong", "strong",
            "italic", "em", "em", "em",
            "underline", "u", "strike", "s",
            "code", "code");

    private BlogDocumentHtml() {
    }

    /** The document's HTML, or an empty string for a body with nothing renderable in it. */
    public static String render(JsonNode body) {
        if (body == null || body.isNull()) return "";
        // V12 turned the seeded posts into documents; a database that has not run it still holds
        // the older flat array of blocks, which blog-render.js also still reads.
        if (body.isArray()) return legacy(body);
        StringBuilder out = new StringBuilder();
        content(body, out);
        return out.toString();
    }

    /** The first {@code limit} characters of the document's own words — a page's description. */
    public static String excerpt(JsonNode body, int limit) {
        String text = plainText(body).replaceAll("\\s+", " ").trim();
        if (text.length() <= limit) return text;
        String cut = text.substring(0, limit);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > limit / 2 ? cut.substring(0, lastSpace) : cut).trim() + "…";
    }

    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Only http(s), checked here as well as in the editor: the editor is a convenience, not a gate. */
    static String safeHref(String href) {
        if (href == null) return null;
        String trimmed = href.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8) ? trimmed : null;
    }

    private static void content(JsonNode node, StringBuilder out) {
        for (JsonNode child : node.path("content")) node(child, out);
    }

    private static void node(JsonNode node, StringBuilder out) {
        String type = node.path("type").asString("");
        switch (type) {
            case "text" -> text(node, out);
            case "paragraph" -> wrap("p", node, out);
            case "heading" -> {
                int level = Math.clamp(node.path("attrs").path("level").asInt(2), 2, 4);
                wrap("h" + level, node, out);
            }
            case "bulletList" -> wrap("ul", node, out);
            case "orderedList" -> wrap("ol", node, out);
            case "listItem" -> wrap("li", node, out);
            case "blockquote" -> wrap("blockquote", node, out);
            case "codeBlock" -> {
                out.append("<pre><code>");
                content(node, out);
                out.append("</code></pre>");
            }
            case "horizontalRule" -> out.append("<hr/>");
            case "hardBreak" -> out.append("<br/>");
            case "image" -> image(node.path("attrs"), out);
            default -> {
                // Whatever it was, it had text in it; show that rather than nothing.
                String fallback = plainText(node);
                if (!fallback.isBlank()) out.append("<p>").append(escape(fallback)).append("</p>");
            }
        }
    }

    private static void wrap(String tag, JsonNode node, StringBuilder out) {
        out.append('<').append(tag).append('>');
        content(node, out);
        out.append("</").append(tag).append('>');
    }

    private static void text(JsonNode node, StringBuilder out) {
        String value = escape(node.path("text").asString(""));
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (JsonNode mark : node.path("marks")) {
            String type = mark.path("type").asString("");
            if ("link".equals(type)) {
                String href = safeHref(mark.path("attrs").path("href").asString(null));
                // A link that will not be followed is still text worth reading.
                if (href == null) continue;
                open.append("<a href=\"").append(escape(href)).append("\" rel=\"noopener noreferrer\">");
                close.insert(0, "</a>");
            } else if (MARK_TAGS.containsKey(type)) {
                open.append('<').append(MARK_TAGS.get(type)).append('>');
                close.insert(0, "</" + MARK_TAGS.get(type) + ">");
            }
        }
        out.append(open).append(value).append(close);
    }

    /**
     * The stored width and height are written out, the same reason the editor keeps them and the
     * public page reserves a box from them: an image that arrives without one moves the text under
     * it as it loads.
     */
    private static void image(JsonNode attrs, StringBuilder out) {
        String src = safeHref(attrs.path("src").asString(null));
        if (src == null) return;
        out.append("<figure><img src=\"").append(escape(src)).append('"')
                .append(" alt=\"").append(escape(attrs.path("alt").asString(""))).append('"');
        if (attrs.path("width").asInt(0) > 0) out.append(" width=\"").append(attrs.path("width").asInt()).append('"');
        if (attrs.path("height").asInt(0) > 0) out.append(" height=\"").append(attrs.path("height").asInt()).append('"');
        out.append(" loading=\"lazy\"/></figure>");
    }

    private static String legacy(JsonNode blocks) {
        StringBuilder out = new StringBuilder();
        for (JsonNode block : blocks) {
            if ("image".equals(block.path("type").asString(""))) {
                image(block, out);
                continue;
            }
            String text = block.path("text").asString("");
            if (!text.isBlank()) out.append("<p>").append(escape(text)).append("</p>");
        }
        return out.toString();
    }

    private static String plainText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonNode child : node) out.append(plainText(child)).append(' ');
            return out.toString();
        }
        if ("text".equals(node.path("type").asString(""))) return node.path("text").asString("");
        StringBuilder out = new StringBuilder();
        for (JsonNode child : node.path("content")) {
            out.append(plainText(child));
            if (List.of("paragraph", "heading", "listItem", "blockquote").contains(child.path("type").asString(""))) {
                out.append(' ');
            }
        }
        return out.toString();
    }
}
