package com.example.candles.api;

import com.example.candles.media.MediaStorageService;
import com.example.candles.media.UploadedMedia;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaStorageService mediaStorageService;

    public MediaController(MediaStorageService mediaStorageService) {
        this.mediaStorageService = mediaStorageService;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResponse upload(@RequestPart("file") MultipartFile file,
                                       @RequestParam(defaultValue = "candles/blog") String folder) {
        UploadedMedia uploaded = mediaStorageService.uploadImage(folder, file);
        return new MediaUploadResponse(uploaded.publicId(), uploaded.url());
    }

    @DeleteMapping("/images")
    public void delete(@RequestParam String publicId) {
        mediaStorageService.deleteImage(publicId);
    }

    public record MediaUploadResponse(String publicId, String url) {
    }
}
