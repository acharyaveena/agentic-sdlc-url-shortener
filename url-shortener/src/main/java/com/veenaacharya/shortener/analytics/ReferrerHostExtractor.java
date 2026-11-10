package com.veenaacharya.shortener.analytics;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReferrerHostExtractor {

    static final int MAX_HOST_LENGTH = 253;

    /** Returns the lowercased host of an http/https referrer, or null if absent or unusable. */
    public String extract(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(referer.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.length() > MAX_HOST_LENGTH ? null : host;
        } catch (Exception e) {
            return null;
        }
    }
}
