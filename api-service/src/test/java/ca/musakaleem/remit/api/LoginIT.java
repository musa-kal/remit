package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEvent;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.security.TokenClaims;
import ca.musakaleem.remit.api.support.AbstractApiIT;
import ca.musakaleem.remit.api.user.AppUser;
import ca.musakaleem.remit.api.user.AppUserRepository;
import ca.musakaleem.remit.api.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

/** Login against the seeded users: tokens, failures, lockout and their audit documents. */
class LoginIT extends AbstractApiIT {

  @Autowired AppUserRepository users;

  @Autowired PasswordEncoder passwordEncoder;

  @Autowired ObjectMapper objectMapper;

  @Test
  void theSeededUsersExistWithBcryptHashesAndTheirRoles() {
    AppUser operator = users.findByUsername("operator").orElseThrow();
    AppUser admin = users.findByUsername("admin").orElseThrow();

    assertThat(operator.getRoles()).containsExactly(Role.OPERATOR);
    assertThat(admin.getRoles()).containsExactlyInAnyOrder(Role.OPERATOR, Role.ADMIN);
    assertThat(operator.getPasswordHash()).startsWith("$2a$").doesNotContain(OPERATOR_PASSWORD);
    assertThat(passwordEncoder.matches(ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
  }

  @Test
  void aValidLoginReturnsABearerTokenThatWorksAndIsAudited() throws Exception {
    JsonNode body =
        json(
            login("admin", ADMIN_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)));

    String token = body.path("accessToken").asText();
    TokenClaims claims = jwtService.parse(token);
    assertThat(claims.username()).isEqualTo("admin");
    assertThat(claims.roles()).containsExactlyInAnyOrder("OPERATOR", "ADMIN");
    assertThat(claims.expiresAt()).isEqualTo(claims.issuedAt().plusSeconds(900));
    mvc.perform(get("/api/v1/audit-events").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    AuditEvent audit =
        eventually(
            () ->
                auditEvents("admin").stream()
                    .filter(e -> e.eventType() == AuditEventType.LOGIN_SUCCESS)
                    .filter(e -> e.occurredAt().isAfter(claims.issuedAt().minusSeconds(1)))
                    .findFirst()
                    .orElse(null));
    assertThat(audit.actor()).isEqualTo("user:admin");
    assertThat(audit.entityType()).isEqualTo(AuditEntityType.USER);
    assertThat(audit.details()).containsEntry("roles", List.of("ADMIN", "OPERATOR"));
  }

  @Test
  void aWrongPasswordIs401ProblemAndAuditedAsFailure() throws Exception {
    String username = newUser("wrongpw");

    login(username, "not-the-password")
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.detail").value("Invalid username or password"))
        .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"));

    List<AuditEvent> audit = auditEvents(username);
    assertThat(audit)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo(AuditEventType.LOGIN_FAILURE);
              assertThat(e.actor()).isEqualTo("user:" + username);
              assertThat(e.details()).containsEntry("reason", "BAD_CREDENTIALS");
            });
  }

  @Test
  void anUnknownUserGetsTheSameAnswerAsAWrongPassword() throws Exception {
    login("nobody-" + UUID.randomUUID().toString().substring(0, 8), "whatever")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Invalid username or password"));
  }

  @Test
  void aMissingFieldIs400WithFieldErrors() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(
            jsonPath("$.errors[*].field", Matchers.containsInAnyOrder("username", "password")));
  }

  @Test
  void aBodyThatIsNotJsonIs400() throws Exception {
    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("The request body is missing or not valid JSON"));
  }

  @Test
  void fiveFailuresLockTheUsernameEvenForTheRightPassword() throws Exception {
    String username = newUser("lockout");
    for (int i = 0; i < 5; i++) {
      login(username, "wrong-" + i).andExpect(status().isUnauthorized());
    }

    login(username, "right-password")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", Matchers.matchesPattern("[1-9][0-9]*")))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.detail").value("Too many failed logins; try again later"));

    assertThat(auditEvents(username))
        .extracting(e -> e.details().get("reason"))
        .containsExactly(
            "BAD_CREDENTIALS",
            "BAD_CREDENTIALS",
            "BAD_CREDENTIALS",
            "BAD_CREDENTIALS",
            "BAD_CREDENTIALS",
            "LOCKED");
    login("operator", OPERATOR_PASSWORD).andExpect(status().isOk());
  }

  @Test
  void aDisabledUserCannotLogIn() throws Exception {
    String username = newUser("disabled");
    AppUser user = users.findByUsername(username).orElseThrow();
    user.disable();
    users.saveAndFlush(user);

    login(username, "right-password").andExpect(status().isUnauthorized());
    assertThat(auditEvents(username))
        .singleElement()
        .satisfies(e -> assertThat(e.details()).containsEntry("reason", "DISABLED"));
  }

  private String newUser(String prefix) {
    String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    users.saveAndFlush(
        new AppUser(
            username,
            passwordEncoder.encode("right-password"),
            Set.of(Role.OPERATOR),
            Instant.now()));
    return username;
  }

  private ResultActions login(String username, String password) throws Exception {
    return mvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    java.util.Map.of("username", username, "password", password))));
  }

  private JsonNode json(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }
}
