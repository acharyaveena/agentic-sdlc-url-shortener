package com.veenaacharya.shortener.validation;

import com.veenaacharya.shortener.config.AppProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class UrlValidator {

    private final int maxLength;
    private final String baseHost;
    private final Set<String> blockedHosts;

    public UrlValidator(AppProperties properties) {
        this.maxLength = properties.getMaxUrlLength();
        this.baseHost = hostOf(properties.getBaseUrl());
        this.blockedHosts = properties.getBlockedHosts().stream()
                .map(UrlValidator::normalizeHost)
                .collect(Collectors.toSet());
    }

    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("URL must not be blank");
        }
        String url = raw.trim();
        if (url.length() > maxLength) {
            throw new InvalidUrlException("URL must not be longer than " + maxLength + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is malformed");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidUrlException("Only http and https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new InvalidUrlException("URL must have a host");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidUrlException("URLs with user-info are not allowed");
        }
        String normalized = normalizeHost(host);
        if (normalized.equals(baseHost) || blockedHosts.contains(normalized)) {
            throw new InvalidUrlException("Self-referencing links are not allowed");
        }
        return url;
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : normalizeHost(host);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid app.base-url: " + url, e);
        }
    }

    private static String normalizeHost(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }
}
