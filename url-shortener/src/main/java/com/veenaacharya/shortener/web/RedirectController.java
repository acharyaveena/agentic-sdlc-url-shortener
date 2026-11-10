package com.veenaacharya.shortener.web;

import com.veenaacharya.shortener.link.ClickMetadata;
import com.veenaacharya.shortener.link.LinkService;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final LinkService service;

    public RedirectController(LinkService service) {
        this.service = service;
    }

    @GetMapping("/{code:[0-9A-Za-z]{1,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @RequestHeader(value = HttpHeaders.REFERER, required = false) String referer) {
        String target = service.resolve(code, new ClickMetadata(userAgent, referer));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
