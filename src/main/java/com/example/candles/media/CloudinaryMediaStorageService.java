package com.example.candles.media;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.candles.config.MediaProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
public class CloudinaryMediaStorageService implements MediaStorageService {

    private final Cloudinary cloudinary;
    private final MediaProperties properties;

    public CloudinaryMediaStorageService(Cloudinary cloudinary, MediaProperties properties) {
        this.cloudinary = cloudinary;
        this.properties = properties;
    }

    @Override
    public UploadedMedia uploadImage(String folder, MultipartFile file) {
        validateImage(file);
        requireConfigured();
        try {
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", folder,
                    "public_id", UUID.randomUUID().toString(),
                    "resource_type", "image",
                    "overwrite", false));
            String publicId = (String) result.get("public_id");
            String secureUrl = (String) result.get("secure_url");
            return new UploadedMedia(publicId, secureUrl);
        } catch (IOException e) {
            throw new IllegalStateException("Không tải lên được ảnh.", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cloudinary từ chối ảnh: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteImage(String publicId) {
        requireConfigured();
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.asMap(
                    "resource_type", "image",
                    "invalidate", true));
        } catch (IOException e) {
            throw new IllegalStateException("Không xoá được ảnh.", e);
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File ảnh trống.");
        }
        if (file.getSize() > properties.maxImageSizeBytes()) {
            throw new IllegalArgumentException("File ảnh vượt quá dung lượng cho phép.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !(contentType.equals("image/jpeg")
                || contentType.equals("image/png")
                || contentType.equals("image/webp"))) {
            throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP.");
        }
    }

    private void requireConfigured() {
        if (!properties.cloudinary().isConfigured()) {
            throw new IllegalStateException("Cloudinary chưa được cấu hình.");
        }
    }
}
