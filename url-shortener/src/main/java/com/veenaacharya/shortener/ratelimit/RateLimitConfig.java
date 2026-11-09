package com.veenaacharya.shortener.ratelimit;

import com.veenaacharya.shortener.config.AppProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final FixedWindowRateLimiter limiter;

    public RateLimitConfig(AppProperties properties, Clock clock) {
        this.limiter = new FixedWindowRateLimiter(
                properties.getRateLimit().getRequests(), properties.getRateLimit().getWindow(), clock);
    }

    @Bean
    public FixedWindowRateLimiter fixedWindowRateLimiter() {
        return limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter)).addPathPatterns("/api/v1/links");
    }
}
