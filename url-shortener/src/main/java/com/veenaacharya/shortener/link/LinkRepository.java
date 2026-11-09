package com.veenaacharya.shortener.link;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface LinkRepository extends CrudRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);

    @Modifying
    @Query("UPDATE links SET click_count = click_count + 1 WHERE short_code = :code")
    int incrementClickCount(@Param("code") String code);

    @Modifying
    @Query("DELETE FROM links WHERE expires_at IS NOT NULL AND expires_at < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
