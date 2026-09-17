package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.support.AbstractApiIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exports the OpenAPI document the running application serves to {@code docs/openapi.json} on every
 * {@code verify}, so the committed file is always the real output. The document has a fixed server
 * URL and sorted keys, so it only changes when the API changes.
 */
class OpenApiExportIT extends AbstractApiIT {

  /** Relative to the api-service module, where failsafe runs. */
  private static final Path TARGET = Path.of("..", "docs", "openapi.json");

  @Test
  void exportsTheOpenApiDocumentWithEveryEndpointAndBearerAuth() throws Exception {
    String body =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    JsonNode document = mapper.readTree(body);

    assertThat(document.path("openapi").asText()).startsWith("3.");
    assertThat(
            document
                .path("components")
                .path("securitySchemes")
                .path("bearerAuth")
                .path("type")
                .asText())
        .isEqualTo("http");
    assertThat(document.path("paths").fieldNames())
        .toIterable()
        .containsAll(
            List.of(
                "/api/v1/auth/login",
                "/api/v1/batches",
                "/api/v1/batches/{batchId}",
                "/api/v1/batches/{batchId}/instructions",
                "/api/v1/batches/{batchId}/rejections",
                "/api/v1/instructions/{idempotencyKey}",
                "/api/v1/dead-letters",
                "/api/v1/dead-letters/{id}/replay",
                "/api/v1/audit-events",
                "/api/v1/stats/summary"));
    assertThat(document.path("paths").path("/api/v1/auth/login").path("post").path("security"))
        .as("login is public")
        .hasSize(0);

    Files.createDirectories(TARGET.getParent());
    Files.writeString(
        TARGET,
        mapper.writeValueAsString(document).replace("\r\n", "\n") + "\n",
        StandardCharsets.UTF_8);
    assertThat(TARGET).exists();
  }
}
