package com.veenaacharya.shortener.link;

/** The User-Agent and Referer headers for the click currently being recorded. */
public record ClickMetadata(String userAgent, String referer) {

    private static final ClickMetadata NONE = new ClickMetadata(null, null);

    public static ClickMetadata none() {
        return NONE;
    }
}
