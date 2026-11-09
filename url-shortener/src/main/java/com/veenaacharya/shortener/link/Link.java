package com.veenaacharya.shortener.link;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("links")
public record Link(
        @Id Long id,
        @Column("short_code") String shortCode,
        @Column("long_url") String longUrl,
        @Column("click_count") long clickCount,
        @Column("created_at") Instant createdAt,
        @Column("expires_at") Instant expiresAt) {

    /** Convenience constructor for links that never expire. */
    public Link(Long id, String shortCode, String longUrl, long clickCount, Instant createdAt) {
        this(id, shortCode, longUrl, clickCount, createdAt, null);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
