package com.veenaacharya.shortener.code;

import com.veenaacharya.shortener.config.AppProperties;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class RandomBase62CodeGenerator implements ShortCodeGenerator {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public RandomBase62CodeGenerator(AppProperties properties) {
        this.length = properties.getCodeLength();
    }

    @Override
    public String next() {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
