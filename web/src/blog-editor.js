/**
 * The blog editor: Tiptap, bundled for the admin page only.
 *
 * Exposes `window.CandleEditor.mount(element, options)` and hands back a small handle the
 * plain-JS admin module drives. Nothing about Tiptap leaks past that handle, and nothing about
 * this bundle reaches the public blog — `blog-render.js` draws the same documents there with
 * its own walker, so a reader never downloads an editor.
 *
 * The document it produces is ordinary ProseMirror JSON. Every node type below has to have a
 * matching branch in `static/blog-render.js`, or the public page will not draw it. That
 * coupling is the price of not shipping the schema twice; the renderer warns loudly rather
 * than silently dropping anything it does not know.
 */
import { Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import Link from "@tiptap/extension-link";
import Image from "@tiptap/extension-image";
import Placeholder from "@tiptap/extension-placeholder";
import TextAlign from "@tiptap/extension-text-align";
import CharacterCount from "@tiptap/extension-character-count";
import FileHandler from "@tiptap/extension-file-handler";

/** http and https only. An href is a public page's most direct route to stored XSS. */
const SAFE_PROTOCOL = /^https?:\/\//i;

function safeHref(href) {
    return typeof href === "string" && SAFE_PROTOCOL.test(href.trim()) ? href.trim() : null;
}

/**
 * Image with intrinsic dimensions.
 *
 * Tiptap's Image carries src/alt/title and nothing else, but the blog reserves an image's box
 * from its width and height so the text below it does not jump when the image arrives. Losing
 * them here would quietly undo that, which is why this is a custom node rather than the stock
 * one.
 */
const SizedImage = Image.extend({
    addAttributes() {
        return {
            ...this.parent?.(),
            width: {
                default: null,
                parseHTML: (el) => el.getAttribute("width"),
                renderHTML: (attrs) => (attrs.width ? { width: attrs.width } : {}),
            },
            height: {
                default: null,
                parseHTML: (el) => el.getAttribute("height"),
                renderHTML: (attrs) => (attrs.height ? { height: attrs.height } : {}),
            },
        };
    },
});

/**
 * Uploads a dropped or pasted file and inserts it at `pos`.
 *
 * The upload response carries the stored dimensions, which is the only reason a pasted image
 * gets the same layout-shift protection as one picked from the library.
 */
async function uploadAndInsert(editor, file, pos, options) {
    const notify = options.onStatus || (() => {});
    try {
        notify("Đang tải ảnh lên…");
        const media = await options.upload(file);
        editor
            .chain()
            .focus()
            .insertContentAt(pos ?? editor.state.selection.anchor, {
                type: "image",
                attrs: {
                    src: media.url,
                    alt: "",
                    width: media.width || null,
                    height: media.height || null,
                },
            })
            .run();
        notify("");
    } catch (e) {
        notify("Không tải được ảnh: " + e.message);
    }
}

function mount(element, options = {}) {
    const editor = new Editor({
        element,
        extensions: [
            StarterKit.configure({ link: false }),
            Underline,
            Link.configure({
                openOnClick: false,
                autolink: true,
                // Tiptap's own guard. blog-render.js checks again on the way out: an editor
                // is a convenience, never the security boundary.
                protocols: ["http", "https"],
                validate: (href) => !!safeHref(href),
            }),
            SizedImage,
            Placeholder.configure({ placeholder: options.placeholder || "" }),
            TextAlign.configure({ types: ["heading", "paragraph"] }),
            CharacterCount,
            FileHandler.configure({
                allowedMimeTypes: ["image/jpeg", "image/png", "image/webp"],
                onDrop: (current, files, pos) => {
                    files.forEach((file) => uploadAndInsert(current, file, pos, options));
                },
                onPaste: (current, files) => {
                    files.forEach((file) => uploadAndInsert(current, file, null, options));
                },
            }),
        ],
        content: options.content || { type: "doc", content: [{ type: "paragraph" }] },
        onUpdate: () => {
            if (options.onUpdate) options.onUpdate();
        },
    });

    return {
        /** The document, as it goes into the `body` jsonb column. */
        json: () => editor.getJSON(),
        setContent: (doc) => editor.commands.setContent(doc || { type: "doc", content: [{ type: "paragraph" }] }),
        characters: () => editor.storage.characterCount.characters(),
        words: () => editor.storage.characterCount.words(),
        focus: () => editor.commands.focus(),
        destroy: () => editor.destroy(),

        /* Toolbar surface. The admin module owns the buttons and their markup; this only says
           what a button does and whether it is currently on. */
        can: (name) => editor.isActive(name),
        isActive: (name, attrs) => editor.isActive(name, attrs),
        run: (name, attrs) => {
            const chain = editor.chain().focus();
            const actions = {
                bold: () => chain.toggleBold(),
                italic: () => chain.toggleItalic(),
                underline: () => chain.toggleUnderline(),
                strike: () => chain.toggleStrike(),
                code: () => chain.toggleCode(),
                h2: () => chain.toggleHeading({ level: 2 }),
                h3: () => chain.toggleHeading({ level: 3 }),
                bulletList: () => chain.toggleBulletList(),
                orderedList: () => chain.toggleOrderedList(),
                blockquote: () => chain.toggleBlockquote(),
                codeBlock: () => chain.toggleCodeBlock(),
                horizontalRule: () => chain.setHorizontalRule(),
                alignLeft: () => chain.setTextAlign("left"),
                alignCenter: () => chain.setTextAlign("center"),
                alignRight: () => chain.setTextAlign("right"),
                undo: () => chain.undo(),
                redo: () => chain.redo(),
            };
            const action = actions[name];
            if (action) action().run();
        },
        setLink: (href) => {
            const safe = safeHref(href);
            if (!safe) {
                editor.chain().focus().unsetLink().run();
                return false;
            }
            editor.chain().focus().extendMarkRange("link").setLink({ href: safe }).run();
            return true;
        },
        unsetLink: () => editor.chain().focus().unsetLink().run(),
        insertImage: (media) => {
            editor.chain().focus().insertContent({
                type: "image",
                attrs: {
                    src: media.url || media.deliveryUrl,
                    alt: media.alt || "",
                    width: media.width || null,
                    height: media.height || null,
                },
            }).run();
        },
        onSelection: (handler) => {
            editor.on("selectionUpdate", handler);
            editor.on("transaction", handler);
        },
    };
}

export { mount, safeHref };
