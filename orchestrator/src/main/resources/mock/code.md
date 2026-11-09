Mock implementation of the code stage.

FILE: url-shortener/src/main/java/com/veenaacharya/shortener/ShortCodeGenerator.java
````java
package com.veenaacharya.shortener;

import java.security.SecureRandom;

/** Generates random Base62 short codes. */
public class ShortCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
````

Notes: mock output only.
