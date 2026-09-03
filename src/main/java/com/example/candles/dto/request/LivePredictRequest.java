package com.example.candles.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LivePredictRequest(
        @NotBlank String asset,
        @Pattern(regexp = "LONG|SHORT", message = "phải là LONG hoặc SHORT") String direction
) {
}
