package com.example.candles.media;

import org.springframework.web.multipart.MultipartFile;

public interface MediaStorageService {

    UploadedMedia uploadImage(String folder, MultipartFile file);

    void deleteImage(String publicId);
}
