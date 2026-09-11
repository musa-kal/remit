package ca.musakaleem.remit.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/auth/login}.
 *
 * @param username login name
 * @param password password
 */
public record LoginRequest(
    @NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 200) String password) {

  @Override
  public String toString() {
    // Keeps the password out of any log line or debugger view that prints the request.
    return "LoginRequest[username=" + username + ", password=***]";
  }
}
