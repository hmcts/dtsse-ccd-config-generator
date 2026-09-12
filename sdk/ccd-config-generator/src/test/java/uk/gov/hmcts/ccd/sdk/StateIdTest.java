package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.Test;

public class StateIdTest {

  enum PlainState {
    Open,
    Submitted
  }

  enum JsonPropertyState {
    Open,
    @JsonProperty("PREPARE_FOR_HEARING")
    CASE_MANAGEMENT
  }

  /**
   * An enum whose {@code toString()} is overridden (via {@code @JsonValue}) to differ from the
   * constant name and which carries no {@code @JsonProperty} — the SSCS shape. StateId must not
   * throw and must resolve to {@code toString()}.
   */
  enum OverriddenToStringState {
    APPEAL_CREATED("appealCreated"),
    WITH_DWP("withDwp");

    private final String id;

    OverriddenToStringState(String id) {
      this.id = id;
    }

    @JsonValue
    @Override
    public String toString() {
      return id;
    }
  }

  @Test
  public void plainConstantResolvesToConstantName() {
    assertThat(StateId.of(PlainState.Open)).isEqualTo("Open");
    assertThat(StateId.of(PlainState.Submitted)).isEqualTo("Submitted");
  }

  @Test
  public void jsonPropertyOverridesTheStateId() {
    assertThat(StateId.of(JsonPropertyState.Open)).isEqualTo("Open");
    assertThat(StateId.of(JsonPropertyState.CASE_MANAGEMENT)).isEqualTo("PREPARE_FOR_HEARING");
  }

  @Test
  public void overriddenToStringDoesNotThrowAndResolvesToToString() {
    assertThatCode(() -> StateId.of(OverriddenToStringState.APPEAL_CREATED))
        .doesNotThrowAnyException();
    assertThat(StateId.of(OverriddenToStringState.APPEAL_CREATED)).isEqualTo("appealCreated");
    assertThat(StateId.of(OverriddenToStringState.WITH_DWP)).isEqualTo("withDwp");
  }

  @Test
  public void nullConstantResolvesToNull() {
    assertThat(StateId.of(null)).isNull();
  }
}
