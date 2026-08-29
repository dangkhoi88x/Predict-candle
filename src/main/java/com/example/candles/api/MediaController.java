package com.example.candles.api;

import com.example.candles.config.MediaProperties;
import com.example.candles.media.MediaStorageService;
import com.example.candles.media.UploadedMedia;
import com.example.candles.repository.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Writes to the project's Cloudinary account, so access is deliberately narrower than the
 * rest of the API. SecurityConfig already requires an authenticated caller; that alone would
 * only mean "anyone who connected a wallet", which on a public game is everyone. The wallet
 * behind the token therefore also has to appear in candles.media.admin-wallets.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaStorageService mediaStorageService;
    private final UserRepository userRepository;
    private final MediaProperties mediaProperties;

    public MediaController(MediaStorageService mediaStorageService,
                           UserRepository userRepository,
                           MediaProperties mediaProperties) {
        this.mediaStorageService = mediaStorageService;
        this.userRepository = userRepository;
        this.mediaProperties = mediaProperties;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadResponse upload(@RequestPart("file") MultipartFile file,
                                       @RequestParam(defaultValue = "candles/blog") String folder) {
        requireMediaAdmin();
        UploadedMedia uploaded = mediaStorageService.uploadImage(folder, file);
        return new MediaUploadResponse(uploaded.publicId(), uploaded.url());
    }

    @DeleteMapping("/images")
    public void delete(@RequestParam String publicId) {
        requireMediaAdmin();
        mediaStorageService.deleteImage(publicId);
    }

    private void requireMediaAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new AccessDeniedException("Cần đăng nhập để dùng chức năng này.");
        }
        // The token carries only the user id, so the wallet has to be read back here. These
        // endpoints are called rarely enough that the lookup costs nothing worth avoiding.
        String wallet = userRepository.findById(userId)
                .map(user -> user.getWalletAddress())
                .orElseThrow(() -> new AccessDeniedException("Tài khoản không còn tồn tại."));
        if (!mediaProperties.isMediaAdmin(wallet)) {
            throw new AccessDeniedException("Ví này không có quyền quản lý media.");
        }
    }

    public record MediaUploadResponse(String publicId, String url) {
    }
}
