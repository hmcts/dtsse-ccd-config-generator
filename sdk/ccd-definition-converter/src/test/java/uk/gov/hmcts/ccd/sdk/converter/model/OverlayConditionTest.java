package uk.gov.hmcts.ccd.sdk.converter.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OverlayConditionTest {

  @AfterEach
  void clearProperties() {
    System.clearProperty("CCD_DEF_ENV");
  }

  @Test
  void parsesPositivePredicate() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");

    assertThat(condition.getEnvVar()).isEqualTo("CCD_DEF_ENV");
    assertThat(condition.getExpectedValue()).isEqualTo("prod");
    assertThat(condition.isNegated()).isFalse();
  }

  @Test
  void parsesNegatedPredicate() {
    OverlayCondition condition = OverlayCondition.parse("!CCD_DEF_ENV:prod");

    assertThat(condition.isNegated()).isTrue();
  }

  @Test
  void rejectsMalformedPredicates() {
    assertThatThrownBy(() -> OverlayCondition.parse("CCD_DEF_ENV"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OverlayCondition.parse(":value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OverlayCondition.parse("VAR:"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void evaluatesAgainstSystemPropertyFirst() {
    OverlayCondition prod = OverlayCondition.parse("CCD_DEF_ENV:prod");
    OverlayCondition nonProd = OverlayCondition.parse("!CCD_DEF_ENV:prod");

    assertThat(prod.isActive()).isFalse();
    assertThat(nonProd.isActive()).isTrue();

    System.setProperty("CCD_DEF_ENV", "prod");
    assertThat(prod.isActive()).isTrue();
    assertThat(nonProd.isActive()).isFalse();
  }

  @Test
  void comparesValueCaseInsensitively() {
    System.setProperty("CCD_DEF_ENV", "PROD");

    assertThat(OverlayCondition.parse("CCD_DEF_ENV:prod").isActive()).isTrue();
  }

  @Test
  void mapIsActiveMatches() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");

    assertThat(condition.isActive(Map.of("CCD_DEF_ENV", "prod"))).isTrue();
  }

  @Test
  void mapIsActiveNonMatch() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");

    assertThat(condition.isActive(Map.of("CCD_DEF_ENV", "staging"))).isFalse();
  }

  @Test
  void mapIsActiveNegatedXor() {
    OverlayCondition negated = OverlayCondition.parse("!CCD_DEF_ENV:prod");

    assertThat(negated.isActive(Map.of("CCD_DEF_ENV", "prod"))).isFalse();
    assertThat(negated.isActive(Map.of("CCD_DEF_ENV", "staging"))).isTrue();
  }

  @Test
  void mapIsActiveCaseInsensitive() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");

    assertThat(condition.isActive(Map.of("CCD_DEF_ENV", "PROD"))).isTrue();
  }

  @Test
  void mapIsActiveAbsentKeyResolvesToEmptyString() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");
    OverlayCondition negated = OverlayCondition.parse("!CCD_DEF_ENV:prod");

    assertThat(condition.isActive(Collections.emptyMap())).isFalse();
    assertThat(negated.isActive(Collections.emptyMap())).isTrue();
  }

  @Test
  void mapIsActiveTolerateNullEnv() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");

    assertThat(condition.isActive((Map<String, String>) null)).isFalse();
  }

  @Test
  void mapIsActiveTolerateNullValueInEnv() {
    OverlayCondition condition = OverlayCondition.parse("CCD_DEF_ENV:prod");
    Map<String, String> env = new HashMap<>();
    env.put("CCD_DEF_ENV", null);

    assertThat(condition.isActive(env)).isFalse();
  }
}
