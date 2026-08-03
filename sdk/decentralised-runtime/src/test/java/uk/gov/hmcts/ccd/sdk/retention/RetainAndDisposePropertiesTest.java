package uk.gov.hmcts.ccd.sdk.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RetainAndDisposePropertiesTest {

  @Test
  void bindsMaximumCandidatePercentageByState() {
    var source = new MapConfigurationPropertySource(Map.of(
        RetainAndDisposeProperties.PREFIX + ".maximum-candidate-percentage-by-state.Delete", 100
    ));

    RetainAndDisposeProperties properties = new Binder(source)
        .bind(RetainAndDisposeProperties.PREFIX, Bindable.of(RetainAndDisposeProperties.class))
        .orElseThrow(() -> new AssertionError("Retain and dispose properties were not bound"));

    assertThat(properties.maximumCandidatePercentageFor("Delete")).isEqualTo(100);
    assertThat(properties.maximumCandidatePercentageFor("delete")).isEqualTo(100);
    assertThat(properties.maximumCandidatePercentageFor("DELETE")).isEqualTo(100);
    assertThat(properties.maximumCandidatePercentageFor("Draft")).isEqualTo(5);
  }

  @Test
  void rejectsMaximumCandidatePercentageByStateOutsideValidRange() {
    RetainAndDisposeProperties properties = validProperties();
    properties.getMaximumCandidatePercentageByState().put("Delete", 101);

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("maximum-candidate-percentage-by-state[Delete] must be between 0 and 100");
  }

  @Test
  void rejectsBlankStateOverride() {
    RetainAndDisposeProperties properties = validProperties();
    properties.getMaximumCandidatePercentageByState().put(" ", 100);

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("maximum-candidate-percentage-by-state must not contain a blank state");
  }

  private static RetainAndDisposeProperties validProperties() {
    RetainAndDisposeProperties properties = new RetainAndDisposeProperties();
    properties.getSystemUser().setUsername("system-user@example.com");
    properties.getSystemUser().setPassword("password");
    return properties;
  }
}
