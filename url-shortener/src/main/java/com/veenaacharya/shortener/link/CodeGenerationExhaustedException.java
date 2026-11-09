package com.veenaacharya.shortener.link;

public class CodeGenerationExhaustedException extends RuntimeException {

    public CodeGenerationExhaustedException(int attempts) {
        super("Could not allocate a unique short code after " + attempts + " attempts");
    }
}
