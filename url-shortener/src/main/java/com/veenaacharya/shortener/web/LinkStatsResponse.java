package com.veenaacharya.shortener.web;

import java.time.Instant;

public record LinkStatsResponse(String code, String longUrl, long clickCount, Instant createdAt) {
}
