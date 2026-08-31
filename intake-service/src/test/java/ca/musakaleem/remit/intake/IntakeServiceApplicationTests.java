package ca.musakaleem.remit.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IntakeServiceApplicationTests {

  @Test
  void contextLoadsWithTestProfile(@Autowired ApplicationContext context) {
    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
    assertThat(context.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("intake-service");
  }
}
