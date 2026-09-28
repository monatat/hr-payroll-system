package com.hrpayroll.backend;

import com.hrpayroll.backend.auth.BootstrapAdminProperties;
import com.hrpayroll.backend.demo.DemoDataProperties;
import com.hrpayroll.backend.security.jwt.JwtProperties;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// UserDetailsServiceAutoConfiguration is excluded: this app authenticates
// directly against UserRepository (see AuthService) and authorizes purely
// from JWT claims (see JwtAuthenticationFilter) -- no UserDetailsService is
// ever used, so the default auto-configured one (and its noisy generated
// startup password) would otherwise be dead weight.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({JwtProperties.class, BootstrapAdminProperties.class, DemoDataProperties.class})
public class HrPayrollBackendApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        SpringApplication.run(HrPayrollBackendApplication.class, args);
    }

}
