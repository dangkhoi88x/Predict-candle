package com.example.candles.media;

import java.util.List;

/**
 * One page of the media library. {@code nextCursor} is null on the last page — Cloudinary
 * pages by cursor, not offset, so the client hands this straight back to ask for more.
 */
public record MediaPage(List<StoredMedia> items, String nextCursor) {
}
