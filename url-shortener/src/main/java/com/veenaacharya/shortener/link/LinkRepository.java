package com.veenaacharya.shortener.link;

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
}
