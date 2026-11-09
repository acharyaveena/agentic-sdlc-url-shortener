package com.veenaacharya.shortener.web;

import com.veenaacharya.shortener.config.AppProperties;
import com.veenaacharya.shortener.link.Link;
import com.veenaacharya.shortener.link.LinkService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService service;
    private final AppProperties properties;

    public LinkController(LinkService service, AppProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        Link link = service.create(request.url(), request.ttlSeconds());
        String base = properties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String shortUrl = base + "/" + link.shortCode();
        LinkResponse body = new LinkResponse(link.shortCode(), shortUrl, link.longUrl(), link.createdAt(), link.expiresAt());
        return ResponseEntity.created(URI.create(shortUrl)).body(body);
    }

    @GetMapping("/{code}/stats")
    public LinkStatsResponse stats(@PathVariable String code) {
        Link link = service.stats(code);
        return new LinkStatsResponse(link.shortCode(), link.longUrl(), link.clickCount(), link.createdAt());
    }
}
