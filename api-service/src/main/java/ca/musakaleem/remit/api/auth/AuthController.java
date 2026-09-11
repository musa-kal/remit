package ca.musakaleem.remit.api.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/auth/login}: the only public API endpoint. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Obtain an access token")
public class AuthController {

  private final AuthService authService;

  /**
   * Creates the controller.
   *
   * @param authService login logic
   */
  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  /**
   * Exchanges a username and password for a 15 minute bearer token.
   *
   * @param request credentials
   * @return the token
   */
  @PostMapping("/login")
  @SecurityRequirements
  @Operation(summary = "Log in and receive a bearer token valid for 15 minutes")
  @ApiResponse(responseCode = "200", description = "Logged in")
  @ApiResponse(responseCode = "400", description = "Missing username or password")
  @ApiResponse(responseCode = "401", description = "Invalid username or password")
  @ApiResponse(responseCode = "429", description = "Username locked after 5 failed logins")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }
}
