package com.ticketsystem.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class PlaceholderSecretGuardTest {

  private final PlaceholderSecretGuard guard = new PlaceholderSecretGuard();

  @ParameterizedTest
  @ValueSource(strings = {"REPLACE_ME_with_openssl_rand_hex_24", "change-me", " CHANGEME "})
  void refusesToStartWithThePlaceholderPassword(String placeholder) {
    var env = new MockEnvironment().withProperty("spring.datasource.password", placeholder);

    assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.datasource.password")
        .hasMessageNotContaining(placeholder.trim());
  }

  @Test
  void refusesToStartWithThePlaceholderSmtpPassword() {
    var env =
        new MockEnvironment()
            .withProperty("spring.datasource.password", "9f2c4a7e1b")
            .withProperty("app.mail.smtp.password", "REPLACE_ME");

    assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.mail.smtp.password")
        .hasMessageNotContaining("REPLACE_ME");
  }

  @Test
  void startsWithARealPassword() {
    var env = new MockEnvironment().withProperty("spring.datasource.password", "9f2c4a7e1b");

    assertThatCode(() -> guard.postProcessEnvironment(env, new SpringApplication()))
        .doesNotThrowAnyException();
  }

  @Test
  void anUnsetPasswordIsLeftToTheDatabase() {
    // Testcontainers supplies credentials through connection details, not this property.
    assertThat(PlaceholderSecretGuard.isPlaceholder(null)).isFalse();
    assertThat(PlaceholderSecretGuard.isPlaceholder("")).isFalse();
  }
}
