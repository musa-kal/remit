package ca.musakaleem.remit.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description served at {@code /v3/api-docs} and in Swagger UI at {@code /swagger-ui.html}.
 * Every operation requires the {@code bearerAuth} scheme except login, which opts out with an empty
 * {@code @SecurityRequirements}. The server URL is fixed so the exported docs/openapi.json does not
 * change between builds.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /** Name of the bearer security scheme. */
  public static final String BEARER_AUTH = "bearerAuth";

  /**
   * The API description.
   *
   * @return the OpenAPI model
   */
  @Bean
  public OpenAPI remitOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Remit operator API")
                .version("v1")
                .description(
                    "Monitor payment batches, inspect rejected rows and dead letters, replay"
                        + " dead letters and read the audit trail. Log in with POST"
                        + " /api/v1/auth/login and send the token as Authorization: Bearer."))
        .servers(List.of(new Server().url("http://localhost:8080").description("Local")))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_AUTH,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
  }
}
