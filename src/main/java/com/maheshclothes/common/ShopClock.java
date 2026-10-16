package com.maheshclothes.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** All "what day is it" questions go through here so the shop's timezone is applied consistently. */
@Component
public class ShopClock {

    private final ZoneId zone;

    public ShopClock(AppProperties props) {
        this.zone = ZoneId.of(props.timezone());
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public Instant now() {
        return Instant.now();
    }

    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** Exclusive upper bound for "up to and including this date". */
    public Instant endOfDayExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay(zone).toInstant();
    }

    public LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, zone);
    }

    public LocalDate toLocalDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }
}
