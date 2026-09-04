package ca.musakaleem.remit.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Spring Boot entry point for the intake-service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IntakeServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IntakeServiceApplication.class, args);
  }
}
