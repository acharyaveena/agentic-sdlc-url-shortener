package com.veenaacharya.shortener.web;

import java.time.Instant;

public record LinkResponse(String code, String shortUrl, String longUrl, Instant createdAt) {
}
