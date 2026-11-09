package com.veenaacharya.shortener.web;

import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(@NotBlank String url) {
}
