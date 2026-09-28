package com.hrpayroll.backend.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in local demo-data settings ({@code app.demo.*}). Disabled by default; the passwords have no
 * default anywhere and are blank unless supplied through {@code DEMO_ADMIN_PASSWORD} /
 * {@code DEMO_EMPLOYEE_PASSWORD}. Deliberately no {@code toString()} override, and this object is
 * never logged, so the passwords cannot leak through a string representation.
 */
@ConfigurationProperties(prefix = "app.demo")
public class DemoDataProperties {

    private boolean enabled;

    private String adminPassword;

    private String employeePassword;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getEmployeePassword() {
        return employeePassword;
    }

    public void setEmployeePassword(String employeePassword) {
        this.employeePassword = employeePassword;
    }
}
