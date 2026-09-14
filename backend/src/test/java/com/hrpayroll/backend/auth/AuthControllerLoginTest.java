package com.hrpayroll.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrpayroll.backend.support.SecurityTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedFixtures() {
        SecurityTestFixtures.install(jdbcTemplate, passwordEncoder);
    }

    @AfterEach
    void cleanupFixtures() {
        SecurityTestFixtures.cleanup(jdbcTemplate);
    }

    @Test
    void validAdminLoginSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityTestFixtures.ADMIN_EMAIL, SecurityTestFixtures.ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    void validEmployeeLoginSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityTestFixtures.EMPLOYEE_EMAIL, SecurityTestFixtures.EMPLOYEE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void wrongPasswordReturnsGenericFailure() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityTestFixtures.ADMIN_EMAIL, "not-the-real-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andReturn();

        assertGenericFailureBody(result.getResponse().getContentAsString());
    }

    @Test
    void nonexistentEmailReturnsIdenticalGenericFailure() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("no-such-user@test.local", "whatever123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andReturn();

        assertGenericFailureBody(result.getResponse().getContentAsString());
    }

    @Test
    void disabledAccountReturnsIdenticalGenericFailure() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityTestFixtures.DISABLED_EMAIL, SecurityTestFixtures.DISABLED_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andReturn();

        assertGenericFailureBody(result.getResponse().getContentAsString());
    }

    @Test
    void malformedLoginRequestReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void passwordIsNeverStoredAsPlaintext() {
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, SecurityTestFixtures.ADMIN_EMAIL);

        assertThat(storedHash).isNotEqualTo(SecurityTestFixtures.ADMIN_PASSWORD);
        assertThat(storedHash).startsWith("$2");
        assertThat(passwordEncoder.matches(SecurityTestFixtures.ADMIN_PASSWORD, storedHash)).isTrue();
    }

    @Test
    void loginEventsAreAudited() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(SecurityTestFixtures.ADMIN_EMAIL, SecurityTestFixtures.ADMIN_PASSWORD)));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson(SecurityTestFixtures.ADMIN_EMAIL, "wrong-password")));

        Integer successCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'LOGIN_SUCCESS' AND details->>'email' = ?",
                Integer.class,
                SecurityTestFixtures.ADMIN_EMAIL);
        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'LOGIN_FAILED' AND details->>'email' = ?",
                Integer.class,
                SecurityTestFixtures.ADMIN_EMAIL);

        assertThat(successCount).isGreaterThanOrEqualTo(1);
        assertThat(failedCount).isGreaterThanOrEqualTo(1);

        String allDetails = String.join(
                " ",
                jdbcTemplate.queryForList(
                        "SELECT details::text FROM audit_logs WHERE details->>'email' = ?",
                        String.class,
                        SecurityTestFixtures.ADMIN_EMAIL));
        assertThat(allDetails).doesNotContain(SecurityTestFixtures.ADMIN_PASSWORD);
        assertThat(allDetails).doesNotContain("wrong-password");
    }

    private void assertGenericFailureBody(String bodyA) throws Exception {
        // Re-run the "wrong password" case and diff the JSON structure/status/code/message
        // to lock in that all three failure reasons are indistinguishable.
        String bodyB = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityTestFixtures.ADMIN_EMAIL, "another-wrong-password")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var nodeA = objectMapper.readTree(bodyA);
        var nodeB = objectMapper.readTree(bodyB);
        assertThat(nodeA.get("status")).isEqualTo(nodeB.get("status"));
        assertThat(nodeA.get("code")).isEqualTo(nodeB.get("code"));
        assertThat(nodeA.get("detail")).isEqualTo(nodeB.get("detail"));
        assertThat(nodeA.get("title")).isEqualTo(nodeB.get("title"));
    }

    private String loginJson(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(email, password));
    }
}
