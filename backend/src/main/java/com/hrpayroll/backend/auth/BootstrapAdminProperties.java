package com.hrpayroll.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap-admin")
public class BootstrapAdminProperties {

    /**
     * Both left unset by default -> bootstrap is skipped. This is a
     * demo-appropriate provisioning mechanism only, not a production
     * secret-management solution.
     */
    private String email;

    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
