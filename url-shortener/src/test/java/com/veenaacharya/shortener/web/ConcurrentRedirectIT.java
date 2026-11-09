package com.veenaacharya.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrent;DB_CLOSE_DELAY=-1",
        "app.base-url=https://short.example",
        "app.rate-limit.requests=1000"
})
class ConcurrentRedirectIT {

    private static final int REQUESTS = 50;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void fiftyConcurrentRedirectsCountExactlyFiftyClicks() throws Exception {
        jdbc.update("DELETE FROM links");
        jdbc.update("INSERT INTO links (short_code, long_url, click_count, created_at) "
                + "VALUES ('race123', 'https://example.com/race', 0, CURRENT_TIMESTAMP)");

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/race123")).GET().build();

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            }));
        }
        start.countDown();

        for (Future<HttpResponse<String>> future : futures) {
            HttpResponse<String> response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).contains("https://example.com/race");
        }
        pool.shutdown();

        Long clicks = jdbc.queryForObject("SELECT click_count FROM links WHERE short_code = 'race123'", Long.class);
        assertThat(clicks).isEqualTo(REQUESTS);
    }
}
