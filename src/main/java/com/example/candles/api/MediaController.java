package com.example.candles.api;

import com.example.candles.auth.AdminAccess;
import com.example.candles.media.MediaPage;
import com.example.candles.media.MediaStorageService;
import com.example.candles.media.UploadedMedia;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Writes to the project's Cloudinary account, so access is deliberately narrower than the rest
 * of the API: SecurityConfig requires ROLE_ADMIN, and because these are writes, AdminAccess
 * confirms that against the database rather than the caller's token.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaStorageService mediaStorageService;
    private final AdminAccess adminAccess;

    /**
     * Generous enough to drag a folder in at once, low enough that a stuck retry cannot fill
     * the media account. Every upload is a file kept and paid for on someone else's storage.
     */
    private static final int UPLOADS_PER_MINUTE = 30;

    private final RateLimiter rateLimiter;

    public MediaController(MediaStorageService mediaStorageService, AdminAccess adminAccess,
                           RateLimiter rateLimiter) {
        this.mediaStorageService = mediaStorageService;
        this.adminAccess = adminAccess;
        this.rateLimiter = rateLimiter;
    }

    /**
     * The library listing. A read, but still admin-only: it enumerates everything in the
     * project's media account, which is not something a public endpoint should hand out.
     */
    @GetMapping("/images")
    public MediaPage list(@RequestParam(defaultValue = "candles/blog") String folder,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(defaultValue = "60") int limit) {
        adminAccess.requireAdmin();
        return mediaStorageService.listImages(folder, cursor, limit);
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResponse upload(@RequestPart("file") MultipartFile file,
                                       @RequestParam(defaultValue = "candles/blog") String folder,
                                       HttpServletRequest request) {
        adminAccess.requireAdmin();
        rateLimiter.check("media-upload", UPLOADS_PER_MINUTE, request);
        UploadedMedia uploaded = mediaStorageService.uploadImage(folder, file);
        return new MediaUploadResponse(uploaded.publicId(), uploaded.url(),
                uploaded.width(), uploaded.height());
    }

    @DeleteMapping("/images")
    public void delete(@RequestParam String publicId) {
        adminAccess.requireAdmin();
        mediaStorageService.deleteImage(publicId);
    }

    /** The editor inserts a pasted image straight from this, dimensions included. */
    public record MediaUploadResponse(String publicId, String url, int width, int height) {
    }
}
