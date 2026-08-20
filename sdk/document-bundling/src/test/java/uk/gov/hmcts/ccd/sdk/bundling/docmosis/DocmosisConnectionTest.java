package uk.gov.hmcts.ccd.sdk.bundling.docmosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DocmosisConnectionTest {

  private static final URI CONVERT = URI.create("http://docmosis.local/rs/convert");
  private static final URI RENDER = URI.create("http://docmosis.local/rs/render");
  private static final String KEY = "super-secret-access-key";

  @Test
  void defaultsAreSensiblyBounded() {
    DocmosisConnection connection = DocmosisConnection.withDefaults(CONVERT, RENDER, KEY);

    assertThat(connection.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(connection.readTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(connection.maxSourceBytes()).isEqualTo(100L * 1024 * 1024);
    assertThat(connection.retryAttempts()).isEqualTo(1);
  }

  @Test
  void toStringRedactsTheAccessKey() {
    DocmosisConnection connection = DocmosisConnection.withDefaults(CONVERT, RENDER, KEY);

    assertThat(connection.toString())
        .doesNotContain(KEY)
        .contains("<redacted>")
        .contains("http://docmosis.local/rs/convert")
        .contains("http://docmosis.local/rs/render");
  }

  @Test
  void endpointsMustBeAbsolute() {
    assertThatThrownBy(() -> DocmosisConnection.withDefaults(null, RENDER, KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("convertEndpoint");
    assertThatThrownBy(
        () -> DocmosisConnection.withDefaults(CONVERT, URI.create("/rs/render"), KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("renderEndpoint");
  }

  @Test
  void theAccessKeyMustBeProvided() {
    assertThatThrownBy(() -> DocmosisConnection.withDefaults(CONVERT, RENDER, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("accessKey");
  }

  @Test
  void timeoutsMustBePositiveAndBounded() {
    assertThatThrownBy(() -> new DocmosisConnection(
        CONVERT, RENDER, KEY, Duration.ZERO, Duration.ofSeconds(60), 1024, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(() -> new DocmosisConnection(
        CONVERT, RENDER, KEY, Duration.ofSeconds(10), Duration.ofMinutes(10), 1024, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("readTimeout must not exceed");
  }

  @Test
  void theSizeCeilingAndRetryBudgetMustBeBounded() {
    assertThatThrownBy(() -> new DocmosisConnection(
        CONVERT, RENDER, KEY, Duration.ofSeconds(10), Duration.ofSeconds(60), 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSourceBytes");
    assertThatThrownBy(() -> new DocmosisConnection(
        CONVERT, RENDER, KEY, Duration.ofSeconds(10), Duration.ofSeconds(60), 1024, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryAttempts");
    assertThatThrownBy(() -> new DocmosisConnection(
        CONVERT, RENDER, KEY, Duration.ofSeconds(10), Duration.ofSeconds(60), 1024, 6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryAttempts");
  }

  @Test
  void validationMessagesNeverContainTheAccessKey() {
    assertThatThrownBy(() -> new DocmosisConnection(
        CONVERT, RENDER, KEY, Duration.ZERO, Duration.ofSeconds(60), 1024, 1))
        .hasMessageNotContaining(KEY);
  }
}
