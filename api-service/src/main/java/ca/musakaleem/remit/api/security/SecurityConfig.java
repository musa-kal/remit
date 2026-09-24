package ca.musakaleem.remit.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless Spring Security 6 configuration (FR-SEC-01 to 04): no sessions, CSRF, form or basic
 * login; every request needs a valid bearer token except login, health probes and the OpenAPI
 * documents. Roles are checked per endpoint with {@code @PreAuthorize} (FR-SEC-03).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  /** The only public API endpoint. */
  public static final String LOGIN_PATH = "/api/v1/auth/login";

  private static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    "/swagger-ui.html",
    "/swagger-ui/**",
    "/v3/api-docs",
    "/v3/api-docs/**",
    "/error"
  };

  /**
   * The filter chain.
   *
   * @param http security builder
   * @param jwtService token verifier
   * @param entryPoint 401 writer
   * @param accessDeniedHandler 403 writer
   * @return the chain
   * @throws Exception when the configuration is invalid
   */
  @Bean
  // CSRF protection is off on purpose (reviewed for SonarQube rule S4502): the API is stateless and
  // reads credentials only from the Authorization header, never from cookies or a session, so a
  // cross-site request cannot carry them. See ADR 0004.
  @SuppressWarnings("java:S4502")
  public SecurityFilterChain apiSecurity(
      HttpSecurity http,
      JwtService jwtService,
      ProblemAuthenticationEntryPoint entryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, LOGIN_PATH)
                    .permitAll()
                    .requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService, entryPoint),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * BCrypt for stored passwords (FR-SEC-02).
   *
   * @return the encoder
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * The authentication manager Spring Security builds from the user details service and the
   * password encoder; the login endpoint uses it to check a username and password.
   *
   * @param configuration Spring Security's authentication configuration
   * @return the manager
   * @throws Exception when it cannot be built
   */
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }
}
