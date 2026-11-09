package com.veenaacharya.shortener.support;

import com.veenaacharya.shortener.config.AppProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Builds AppProperties through Spring's binder, the same way the application binds them. */
public final class TestProps {

    private TestProps() {
    }

    public static AppProperties app(Map<String, String> overrides) {
        Map<String, Object> source = new HashMap<>(overrides);
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("app", AppProperties.class);
    }

    public static AppProperties withBaseUrl(String baseUrl) {
        return app(Map.of("app.base-url", baseUrl));
    }

    public static AppProperties defaults() {
        return app(Map.of());
    }
}
