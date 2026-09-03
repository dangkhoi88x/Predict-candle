package com.example.candles.domain;

/**
 * An image already in the media account.
 *
 * Three URLs because they answer three different questions. {@code originalUrl} is the
 * untouched upload. {@code thumbUrl} is what the library grid draws — asking the browser to
 * download a 1.8 MB original to paint it 160 pixels wide is how an admin page becomes slow to
 * open. {@code deliveryUrl} is the one to paste into a post: it carries the same
 * f_auto,q_auto transform every image on the blog already uses.
 */
public record StoredMedia(
        String publicId,
        String originalUrl,
        String thumbUrl,
        String deliveryUrl,
        String format,
        long bytes,
        int width,
        int height,
        String createdAt
) {
}
