package com.veenaacharya.shortener.analytics;

import java.time.LocalDate;

public record DailyClicks(LocalDate date, long clicks) {
}
