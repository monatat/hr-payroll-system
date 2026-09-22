package com.hrpayroll.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the application's {@link Clock} bean is pinned to the explicit business time zone
 * (Asia/Kuala_Lumpur) rather than whichever zone the host machine happens to use.
 */
@SpringBootTest
class TimeConfigTest {

    @Autowired
    private Clock clock;

    @Test
    void clockBeanExists() {
        assertThat(clock).isNotNull();
    }

    @Test
    void clockBeanUsesTheExplicitKualaLumpurZone() {
        // Deliberately never references ZoneId.systemDefault() -- the bean's zone must come from
        // its own explicit configuration, not from whatever zone the host machine happens to use.
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Kuala_Lumpur"));
    }
}
