package com.veenaacharya.shortener.analytics;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ClickEventRepository extends CrudRepository<ClickEvent, Long> {

    @Query("SELECT * FROM click_event WHERE link_id = :linkId "
            + "AND clicked_at >= :from AND clicked_at < :to")
    List<ClickEvent> findInRange(@Param("linkId") Long linkId,
                                 @Param("from") Instant fromInclusive,
                                 @Param("to") Instant toExclusive);
}
