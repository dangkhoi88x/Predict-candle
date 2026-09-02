package com.example.candles.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery URL the library hands out has to be the same shape as the ones already stored
 * in the blog content — those were written by hand and are what the tab renders today. A URL
 * that merely works is not enough: two shapes for the same image would make it impossible to
 * tell later which posts came from where.
 */
class CloudinaryUrlTest {

    /** Taken from a seeded post, with the transform segment removed to stand in for secure_url. */
    private static final String SECURE_URL =
            "https://res.cloudinary.com/dtnigztyn/image/upload/candles/blog/truecrypto/chart-2-typical-bottom";

    @Test
    void splicesTheTransformWhereCloudinaryExpectsIt() {
        assertThat(CloudinaryMediaStorageService.transformed(SECURE_URL, "f_auto,q_auto,w_1120"))
                .isEqualTo("https://res.cloudinary.com/dtnigztyn/image/upload/f_auto,q_auto,w_1120"
                        + "/candles/blog/truecrypto/chart-2-typical-bottom");
    }

    @Test
    void thumbnailAndDeliveryDifferOnlyInWidth() {
        assertThat(CloudinaryMediaStorageService.transformed(SECURE_URL, "f_auto,q_auto,w_320"))
                .isEqualTo(CloudinaryMediaStorageService.transformed(SECURE_URL, "f_auto,q_auto,w_1120")
                        .replace("w_1120", "w_320"));
    }

    @Test
    void aUrlWithNoUploadSegmentIsLeftAlone() {
        assertThat(CloudinaryMediaStorageService.transformed("https://example.com/a.png", "f_auto"))
                .isEqualTo("https://example.com/a.png");
        assertThat(CloudinaryMediaStorageService.transformed(null, "f_auto")).isNull();
    }
}
