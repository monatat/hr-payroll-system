package com.hrpayroll.backend.demo;

/**
 * The fixed, reserved identity set that marks the opt-in local demo dataset. The seeder recognises
 * "its" rows only by these exact values -- there is deliberately no user-configurable marker, so the
 * seed can never claim unrelated rows. Production always uses {@link #PRODUCTION}; tests pass a
 * distinct set (package-private) so they never touch the real demo rows in a shared database.
 */
record DemoIdentities(
        String adminEmail,
        String employeeEmail,
        String engineeringDepartment,
        String financeDepartment,
        String employeeOneCode,
        String employeeOneName,
        String employeeOneIc,
        String employeeTwoCode,
        String employeeTwoName,
        String employeeTwoIc) {

    static final DemoIdentities PRODUCTION = new DemoIdentities(
            "demo.admin@demo.local",
            "demo.employee@demo.local",
            "Demo Engineering",
            "Demo Finance",
            "DEMO-001",
            "Demo Employee One",
            "DEMO-IC-0001",
            "DEMO-002",
            "Demo Employee Two",
            "DEMO-IC-0002");
}
