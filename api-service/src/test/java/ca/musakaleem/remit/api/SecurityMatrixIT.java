package ca.musakaleem.remit.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.security.JwtProperties;
import ca.musakaleem.remit.api.security.JwtService;
import ca.musakaleem.remit.api.support.AbstractApiIT;
import ca.musakaleem.remit.api.support.Fixtures;
import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The FR-SEC-03 role matrix for every endpoint of spec 7.3 with real signed tokens: no token 401,
 * wrong role 403, the right role 2xx. Every error is an RFC 7807 ProblemDetail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityMatrixIT extends AbstractApiIT {

  private final UUID batchId = UUID.randomUUID();
  private PaymentInstruction instruction;
  private long deadLetterId;

  /** An endpoint, the role it needs and the success status. */
  record Endpoint(HttpMethod method, String path, String role, int success) {
    @Override
    public String toString() {
      return method + " " + path.replaceAll("[0-9a-f-]{20,}", "{id}") + " (" + role + ")";
    }
  }

  @BeforeAll
  void insertFixtures() {
    Fixtures fixtures = new Fixtures(jdbc);
    instruction = TestEvents.instruction(batchId, 0);
    fixtures.batch(batchId, "SECURE01", "COMPLETED", Instant.parse("2026-10-15T13:00:00Z"));
    fixtures.instruction(instruction, InstructionStatus.DEAD_LETTERED);
    fixtures.rejection(batchId, 4);
    deadLetterId = fixtures.deadLetter(instruction);
  }

  Stream<Endpoint> endpoints() {
    String key = instruction.idempotencyKey();
    return Stream.of(
        new Endpoint(HttpMethod.GET, "/api/v1/batches", "OPERATOR", 200),
        new Endpoint(HttpMethod.GET, "/api/v1/batches/" + batchId, "OPERATOR", 200),
        new Endpoint(
            HttpMethod.GET, "/api/v1/batches/" + batchId + "/instructions", "OPERATOR", 200),
        new Endpoint(HttpMethod.GET, "/api/v1/batches/" + batchId + "/rejections", "OPERATOR", 200),
        new Endpoint(HttpMethod.GET, "/api/v1/instructions/" + key, "OPERATOR", 200),
        new Endpoint(HttpMethod.GET, "/api/v1/dead-letters", "OPERATOR", 200),
        new Endpoint(
            HttpMethod.POST, "/api/v1/dead-letters/" + deadLetterId + "/replay", "ADMIN", 202),
        new Endpoint(HttpMethod.GET, "/api/v1/audit-events", "ADMIN", 200),
        new Endpoint(HttpMethod.GET, "/api/v1/stats/summary", "OPERATOR", 200));
  }

  @ParameterizedTest(name = "{0}: no token is 401")
  @MethodSource("endpoints")
  void withoutATokenEveryEndpointIs401(Endpoint endpoint) throws Exception {
    mvc.perform(call(endpoint))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.instance").value(endpoint.path()))
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @ParameterizedTest(name = "{0}: wrong role is 403")
  @MethodSource("endpoints")
  void withTheWrongRoleEveryEndpointIs403(Endpoint endpoint) throws Exception {
    String wrongRole = endpoint.role().equals("ADMIN") ? operator() : bearer("guest", "GUEST");

    mvc.perform(call(endpoint).header("Authorization", wrongRole))
        .andExpect(status().isForbidden())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(jsonPath("$.detail").value("Your roles do not allow this operation"));
  }

  @ParameterizedTest(name = "{0}: right role succeeds")
  @MethodSource("endpoints")
  void withTheRightRoleEveryEndpointSucceeds(Endpoint endpoint) throws Exception {
    String token = endpoint.role().equals("ADMIN") ? admin() : operator();

    mvc.perform(call(endpoint).header("Authorization", token))
        .andExpect(status().is(endpoint.success()));
  }

  @Test
  void adminHoldsOperatorToo() throws Exception {
    mvc.perform(request(HttpMethod.GET, "/api/v1/batches").header("Authorization", admin()))
        .andExpect(status().isOk());
  }

  @Test
  void rolesAreNotHierarchical() throws Exception {
    mvc.perform(
            request(HttpMethod.GET, "/api/v1/batches")
                .header("Authorization", bearer("root", "ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void anExpiredTokenIs401WithAnInvalidTokenChallenge() throws Exception {
    JwtService pastIssuer =
        new JwtService(
            new JwtProperties(JWT_SECRET, Duration.ofMinutes(15)),
            Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
    String expired = pastIssuer.issue("admin", List.of("OPERATOR", "ADMIN")).token();

    mvc.perform(
            request(HttpMethod.GET, "/api/v1/batches").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
        .andExpect(jsonPath("$.detail").value("The access token has expired"));
  }

  @Test
  void aTokenSignedWithAnotherSecretIs401() throws Exception {
    JwtService forger =
        new JwtService(
            new JwtProperties(
                "a-completely-different-secret-of-enough-length", Duration.ofMinutes(15)),
            Clock.systemUTC());
    String forged = forger.issue("admin", List.of("OPERATOR", "ADMIN")).token();

    mvc.perform(
            request(HttpMethod.GET, "/api/v1/audit-events")
                .header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("The access token is invalid"));
  }

  @Test
  void anUnknownPathIs401WithoutATokenAnd404WithOne() throws Exception {
    mvc.perform(request(HttpMethod.GET, "/api/v1/nothing-here"))
        .andExpect(status().isUnauthorized());
    mvc.perform(request(HttpMethod.GET, "/api/v1/nothing-here").header("Authorization", operator()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("Exception"))));
  }

  @Test
  void theLoginEndpointIsPublic() throws Exception {
    mvc.perform(
            request(HttpMethod.POST, "/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"operator\",\"password\":\"" + OPERATOR_PASSWORD + "\"}"))
        .andExpect(status().isOk());
  }

  private static MockHttpServletRequestBuilder call(Endpoint endpoint) {
    return request(endpoint.method(), endpoint.path());
  }
}
