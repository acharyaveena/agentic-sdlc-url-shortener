package com.veenaacharya.agentic.orchestrator.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashesTest {

    @Test
    void producesKnownSha256Digests() {
        assertThat(Hashes.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void nullHashesAsEmptyAndDifferentTextHashesDifferently() {
        assertThat(Hashes.sha256(null)).isEqualTo(Hashes.sha256(""));
        assertThat(Hashes.sha256("a")).isNotEqualTo(Hashes.sha256("b")).hasSize(64);
    }
}
