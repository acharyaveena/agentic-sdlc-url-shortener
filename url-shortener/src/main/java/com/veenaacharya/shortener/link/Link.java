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
        @Column("created_at") Instant createdAt) {
}
