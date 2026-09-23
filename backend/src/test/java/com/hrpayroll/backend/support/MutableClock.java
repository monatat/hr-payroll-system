package com.hrpayroll.backend.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Test-only {@link Clock} double whose instant can be moved between/within tests -- the
 * deterministic time source Phase 9 attendance boundary tests need, without depending on the real
 * system clock or the host machine's timezone. Registered as a {@code @Primary} bean the same way
 * {@link ControllablePayslipPdfRenderer} overrides {@code PayslipPdfRenderer}.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
