package com.example.candles.media;

/**
 * {@code width} and {@code height} come back from the upload and are carried through rather
 * than dropped. The blog reserves an image's box from its intrinsic size, so an image pasted
 * straight into the editor has to learn its own dimensions at upload time — there is no second
 * request that would tell it later.
 */
public record UploadedMedia(String publicId, String url, int width, int height) {
}
