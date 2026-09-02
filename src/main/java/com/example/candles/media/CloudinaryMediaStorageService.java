package com.example.candles.media;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.candles.config.MediaProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
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
            return new UploadedMedia(publicId, secureUrl,
                    intValue(result.get("width")), intValue(result.get("height")));
        } catch (IOException e) {
            throw new IllegalStateException("Không tải lên được ảnh.", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cloudinary từ chối ảnh: " + e.getMessage(), e);
        }
    }

    /** Cloudinary returns these as Integer, but a JSON number has no guaranteed Java type. */
    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
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

    @Override
    public MediaPage listImages(String folder, String cursor, int limit) {
        requireConfigured();
        try {
            Map<String, Object> options = new LinkedHashMap<>(Map.of(
                    "type", "upload",
                    "resource_type", "image",
                    "prefix", folder,
                    "max_results", Math.clamp(limit, 1, 100)));
            if (cursor != null && !cursor.isBlank()) {
                options.put("next_cursor", cursor);
            }

            Map<?, ?> response = cloudinary.api().resources(options);
            List<?> resources = (List<?>) response.get("resources");
            List<StoredMedia> items = resources == null ? List.of() : resources.stream()
                    .map(resource -> toStoredMedia((Map<?, ?>) resource))
                    .toList();
            return new MediaPage(items, (String) response.get("next_cursor"));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Không đọc được thư viện ảnh: " + e.getMessage(), e);
        } catch (Exception e) {
            // The Cloudinary Admin API declares a checked Exception, so this is not the
            // catch-all it looks like — it is the only way to call it.
            throw new IllegalStateException("Không đọc được thư viện ảnh.", e);
        }
    }

    private StoredMedia toStoredMedia(Map<?, ?> resource) {
        String secureUrl = (String) resource.get("secure_url");
        return new StoredMedia(
                (String) resource.get("public_id"),
                secureUrl,
                transformed(secureUrl, "f_auto,q_auto,w_320"),
                transformed(secureUrl, "f_auto,q_auto,w_1120"),
                (String) resource.get("format"),
                asLong(resource.get("bytes")),
                (int) asLong(resource.get("width")),
                (int) asLong(resource.get("height")),
                String.valueOf(resource.get("created_at")));
    }

    /**
     * Splices a transform into a delivery URL, which Cloudinary reads from the path segment
     * right after /upload/. Built here rather than with the SDK's url builder so the result is
     * character-for-character the shape already stored in the blog content.
     */
    static String transformed(String secureUrl, String transform) {
        if (secureUrl == null) {
            return null;
        }
        int marker = secureUrl.indexOf("/upload/");
        if (marker < 0) {
            return secureUrl;
        }
        int after = marker + "/upload/".length();
        return secureUrl.substring(0, after) + transform + "/" + secureUrl.substring(after);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
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
