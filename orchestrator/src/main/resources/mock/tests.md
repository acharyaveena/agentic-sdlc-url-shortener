Mock implementation of the tests stage.

FILE: url-shortener/src/test/java/com/veenaacharya/shortener/ShortCodeGeneratorTest.java
````java
package com.veenaacharya.shortener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void codesHaveSevenCharacters() {
        assertEquals(7, generator.next().length());
    }

    @Test
    void codesUseOnlyBase62Characters() {
        for (int i = 0; i < 100; i++) {
            assertTrue(generator.next().matches("[0-9A-Za-z]{7}"));
        }
    }
}
````

## Coverage
- Short code length and alphabet: ShortCodeGeneratorTest.
