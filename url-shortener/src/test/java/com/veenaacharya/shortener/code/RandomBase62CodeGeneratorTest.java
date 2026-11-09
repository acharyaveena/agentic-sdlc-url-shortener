package com.veenaacharya.shortener.code;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.shortener.support.TestProps;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomBase62CodeGeneratorTest {

    private final RandomBase62CodeGenerator generator =
            new RandomBase62CodeGenerator(TestProps.defaults());

    @Test
    void generatesSevenCharacterBase62Codes() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.next();
            assertThat(code).hasSize(7).matches("^[0-9A-Za-z]{7}$");
        }
    }

    @Test
    void generatesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.next());
        }
        assertThat(codes).hasSize(10_000);
    }

    @Test
    void usesBothLettersAndDigitsAcrossManyCodes() {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            all.append(generator.next());
        }
        assertThat(all.toString()).containsPattern("[0-9]").containsPattern("[a-z]").containsPattern("[A-Z]");
    }
}
