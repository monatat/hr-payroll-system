package com.hrpayroll.backend.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's single explicit business time source (Phase 9). Pinned to Asia/Kuala_Lumpur
 * by construction -- deliberately never {@code Clock.systemDefaultZone()} -- so business-date
 * logic (attendance clock-in/out) does not depend on the host machine's timezone, and matches the
 * existing {@code spring.jackson.time-zone}/{@code TimeZone.setDefault} convention already
 * established in {@code application.yml}/{@code HrPayrollBackendApplication}. An ordinary,
 * non-{@code @Primary} bean, so a later test can override it with {@code Clock.fixed(...)} the
 * same way {@code PayrollPdfServiceTest} overrides {@code PayslipPdfRenderer}. Not injected into
 * any existing service -- only future Phase 9 attendance code depends on it.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Kuala_Lumpur"));
    }
}
