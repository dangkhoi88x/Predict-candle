-- The blog body becomes a ProseMirror document.
--
-- It was a flat array of {type:"text"|"image"} blocks, which the old block-builder editor
-- produced and could hold nothing else — no headings, no lists, no emphasis inside a
-- sentence. The editor is now Tiptap, and what Tiptap saves is an ordinary ProseMirror
-- document: {"type":"doc","content":[...]}.
--
-- Same jsonb column either way. Converting here rather than leaving the two shapes to coexist
-- means there is one format in the database to reason about; blog-render.js still reads the
-- old array so an un-migrated copy of this database keeps rendering rather than going blank.
--
-- Only rows still holding an array are touched, so this is safe to re-run and a no-op on a
-- database that already has documents.
UPDATE blog_posts
SET body = jsonb_build_object(
        'type', 'doc',
        'content', COALESCE(
            (
                SELECT jsonb_agg(
                    CASE
                        WHEN block->>'type' = 'image' THEN
                            jsonb_build_object(
                                'type', 'image',
                                'attrs', jsonb_strip_nulls(jsonb_build_object(
                                    'src', block->>'src',
                                    'alt', COALESCE(block->>'alt', ''),
                                    -- w/h become width/height: same numbers, the attribute
                                    -- names the Tiptap image node uses.
                                    'width', block->'w',
                                    'height', block->'h'
                                ))
                            )
                        WHEN COALESCE(block->>'text', '') = '' THEN
                            -- An empty paragraph is a paragraph with no content, not one
                            -- holding an empty text node: ProseMirror rejects the latter.
                            jsonb_build_object('type', 'paragraph')
                        ELSE
                            jsonb_build_object(
                                'type', 'paragraph',
                                'content', jsonb_build_array(jsonb_build_object(
                                    'type', 'text',
                                    'text', block->>'text'
                                ))
                            )
                    END
                    ORDER BY ordinality
                )
                FROM jsonb_array_elements(body) WITH ORDINALITY AS t(block, ordinality)
            ),
            -- A post with an empty body still needs one paragraph to put a caret in.
            jsonb_build_array(jsonb_build_object('type', 'paragraph'))
        )
    )
WHERE jsonb_typeof(body) = 'array';
