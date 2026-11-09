package com.veenaacharya.shortener.link;

public class LinkExpiredException extends RuntimeException {

    public LinkExpiredException(String code) {
        super("Link has expired: " + code);
    }
}
