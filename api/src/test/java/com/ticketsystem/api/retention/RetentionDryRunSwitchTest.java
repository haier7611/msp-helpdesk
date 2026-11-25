package com.ticketsystem.api.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.api.retention.job.RetentionJob;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * PR #22 audit M2: the documented dry-run switch is the environment variable {@code
 * APP_RETENTION_DRY_RUN} (docker-compose.yml passes it through, docs/30-reliability.md documents
 * it). This proves the job's property placeholder resolves from exactly that variable, defaults to
 * false, and ignores the old misspelling that used to be documented.
 */
class RetentionDryRunSwitchTest {

  private static String placeholder() {
    Constructor<?> c = RetentionJob.class.getConstructors()[0];
    return Arrays.stream(c.getParameters())
        .filter(p -> p.getType() == boolean.class)
        .map(Parameter::getAnnotations)
        .flatMap(Arrays::stream)
        .filter(Value.class::isInstance)
        .map(a -> ((Value) a).value())
        .findFirst()
        .orElseThrow();
  }

  private static String resolve(Map<String, Object> env) {
    StandardEnvironment e = new StandardEnvironment();
    e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    e.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env", env));
    return e.resolvePlaceholders(placeholder());
  }

  @Test
  void theDocumentedVariableTurnsDryRunOn() {
    assertThat(placeholder()).isEqualTo("${app.retention.dry-run:false}");
    assertThat(resolve(Map.of("APP_RETENTION_DRY_RUN", "true"))).isEqualTo("true");
  }

  @Test
  void itDefaultsToFalseAndTheOldSpellingDoesNothing() {
    assertThat(resolve(Map.of())).isEqualTo("false");
    assertThat(resolve(Map.of("APP_RETENTION_DRYRUN", "true"))).isEqualTo("false");
  }
}
