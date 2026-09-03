package com.example.candles.media;

import org.springframework.web.multipart.MultipartFile;

public interface MediaStorageService {

    UploadedMedia uploadImage(String folder, MultipartFile file);

    void deleteImage(String publicId);

    /**
     * Images under {@code folder}, newest first. {@code cursor} continues a previous page and
     * is null for the first one.
     */
    MediaPage listImages(String folder, String cursor, int limit);
}
