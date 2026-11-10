package com.veenaacharya.shortener.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public class AppProperties {

    private String baseUrl = "http://localhost:8080";
    private List<String> blockedHosts = new ArrayList<>(List.of("localhost", "127.0.0.1", "::1"));
    private int maxUrlLength = 2048;
    private int codeLength = 7;
    private int maxCodeAttempts = 5;
    private RateLimit rateLimit = new RateLimit();
    private Link link = new Link();
    private Analytics analytics = new Analytics();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<String> getBlockedHosts() {
        return blockedHosts;
    }

    public void setBlockedHosts(List<String> blockedHosts) {
        this.blockedHosts = blockedHosts;
    }

    public int getMaxUrlLength() {
        return maxUrlLength;
    }

    public void setMaxUrlLength(int maxUrlLength) {
        this.maxUrlLength = maxUrlLength;
    }

    public int getCodeLength() {
        return codeLength;
    }

    public void setCodeLength(int codeLength) {
        this.codeLength = codeLength;
    }

    public int getMaxCodeAttempts() {
        return maxCodeAttempts;
    }

    public void setMaxCodeAttempts(int maxCodeAttempts) {
        this.maxCodeAttempts = maxCodeAttempts;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Link getLink() {
        return link;
    }

    public void setLink(Link link) {
        this.link = link;
    }

    public Analytics getAnalytics() {
        return analytics;
    }

    public void setAnalytics(Analytics analytics) {
        this.analytics = analytics;
    }

    public static class RateLimit {

        private int requests = 10;
        private Duration window = Duration.ofMinutes(1);

        public int getRequests() {
            return requests;
        }

        public void setRequests(int requests) {
            this.requests = requests;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public static class Link {

        private Duration defaultTtl = Duration.parse("P30D");
        private Duration cleanupInterval = Duration.parse("PT1H");
        private Duration cleanupGrace = Duration.parse("P7D");

        public Duration getDefaultTtl() {
            return defaultTtl;
        }

        public void setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }

        public Duration getCleanupGrace() {
            return cleanupGrace;
        }

        public void setCleanupGrace(Duration cleanupGrace) {
            this.cleanupGrace = cleanupGrace;
        }
    }

    public static class Analytics {

        private int defaultRangeDays = 30;
        private int maxRangeDays = 366;
        private int topReferrersLimit = 10;

        public int getDefaultRangeDays() {
            return defaultRangeDays;
        }

        public void setDefaultRangeDays(int defaultRangeDays) {
            this.defaultRangeDays = defaultRangeDays;
        }

        public int getMaxRangeDays() {
            return maxRangeDays;
        }

        public void setMaxRangeDays(int maxRangeDays) {
            this.maxRangeDays = maxRangeDays;
        }

        public int getTopReferrersLimit() {
            return topReferrersLimit;
        }

        public void setTopReferrersLimit(int topReferrersLimit) {
            this.topReferrersLimit = topReferrersLimit;
        }
    }
}
