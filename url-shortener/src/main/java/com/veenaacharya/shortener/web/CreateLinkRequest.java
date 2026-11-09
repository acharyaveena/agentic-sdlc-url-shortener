package com.veenaacharya.shortener.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(
        @NotBlank String url,
        @Min(60) @Max(31_536_000) Long ttlSeconds) {
}
