package com.veenaacharya.shortener.analytics;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("click_event")
public record ClickEvent(
        @Id Long id,
        @Column("link_id") Long linkId,
        @Column("clicked_at") Instant clickedAt,
        @Column("referrer_host") String referrerHost,
        @Column("ua_category") UserAgentCategory uaCategory) {
}
