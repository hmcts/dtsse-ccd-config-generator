package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MediaPlaceholderTest {

  @Test
  void buildsWithFullMetadata() {
    MediaPlaceholder media = MediaPlaceholder.builder()
        .accessUrl("https://evidence.example.com/recordings/42")
        .duration(Duration.ofMinutes(90))
        .note("Playback requires case access")
        .build();

    assertThat(media.accessUrl()).isEqualTo("https://evidence.example.com/recordings/42");
    assertThat(media.duration()).contains(Duration.ofMinutes(90));
    assertThat(media.note()).contains("Playback requires case access");
  }

  @Test
  void durationAndNoteAreOptional() {
    MediaPlaceholder media = MediaPlaceholder.builder()
        .accessUrl("https://evidence.example.com/recordings/42")
        .build();

    assertThat(media.duration()).isEmpty();
    assertThat(media.note()).isEmpty();
  }

  @Test
  void requiresAnAccessUrl() {
    assertThatThrownBy(() -> MediaPlaceholder.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("accessUrl");
  }

  @Test
  void rejectsARelativeAccessUrl() {
    assertThatThrownBy(() -> MediaPlaceholder.builder().accessUrl("/recordings/42").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absolute");
  }

  @Test
  void rejectsAnOversizedNote() {
    assertThatThrownBy(() -> MediaPlaceholder.builder()
        .accessUrl("https://evidence.example.com/recordings/42")
        .note("x".repeat(501))
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("note");
  }
}
