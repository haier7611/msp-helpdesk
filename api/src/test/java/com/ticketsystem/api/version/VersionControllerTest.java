package com.ticketsystem.api.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

class VersionControllerTest {

  @Test
  void acceptsShortAndFullCommitIds() {
    assertThat(VersionController.sanitiseSha("0123abc")).isEqualTo("0123abc");
    String full = "0123456789abcdef0123456789abcdef01234567";
    assertThat(VersionController.sanitiseSha(full)).isEqualTo(full);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"unknown", "abc", "0123ABC", "<script>", "0123abc; rm -rf /"})
  void anythingElseBecomesUnknown(String value) {
    assertThat(VersionController.sanitiseSha(value)).isEqualTo("unknown");
  }

  @Test
  void fallsBackWhenThereIsNoBuildInfo() {
    var noBuildInfo = new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class);

    var response = new VersionController(noBuildInfo, "deadbee").version();

    assertThat(response.name()).isEqualTo(VersionController.FALLBACK_NAME);
    assertThat(response.version()).isEqualTo(VersionController.FALLBACK_VERSION);
    assertThat(response.gitSha()).isEqualTo("deadbee");
  }
}
