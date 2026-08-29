package com.example.candles.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record WalletVerifyRequest(
        @NotBlank @Pattern(regexp = "^0x[a-fA-F0-9]{40}$") String address,
        @NotBlank String signature
) {
}
