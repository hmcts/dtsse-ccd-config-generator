package uk.gov.hmcts.ccd.sdk.converter.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubstitutorTest {

  @Test
  void substitutesKnownCcdDefVariable() {
    Map<String, String> env = Map.of("CCD_DEF_BASE_URL", "http://localhost:4000");
    String result = Substitutor.substituteValue(env, "${CCD_DEF_BASE_URL}/callback");
    assertThat(result).isEqualTo("http://localhost:4000/callback");
  }

  @Test
  void ignoresVariablesWithoutCcdDefPrefix() {
    Map<String, String> env = Map.of("OTHER_VAR", "ignored");
    String result = Substitutor.substituteValue(env, "${OTHER_VAR}/path");
    assertThat(result).isEqualTo("${OTHER_VAR}/path");
  }

  @Test
  void leavesPlaceholderVerbatimWhenVariableNotInEnv() {
    Map<String, String> env = Map.of("CCD_DEF_OTHER", "somevalue");
    String result = Substitutor.substituteValue(env, "${CCD_DEF_BASE_URL}/callback");
    assertThat(result).isEqualTo("${CCD_DEF_BASE_URL}/callback");
  }

  @Test
  void replacesAllOccurrencesOfSameVariable() {
    Map<String, String> env = Map.of("CCD_DEF_HOST", "http://host");
    String result = Substitutor.substituteValue(env, "${CCD_DEF_HOST}/a and ${CCD_DEF_HOST}/b");
    assertThat(result).isEqualTo("http://host/a and http://host/b");
  }

  @Test
  void substitutesMultipleDistinctVariables() {
    Map<String, String> env = Map.of(
        "CCD_DEF_HOST", "http://host",
        "CCD_DEF_PATH", "/callback");
    String result = Substitutor.substituteValue(env, "${CCD_DEF_HOST}${CCD_DEF_PATH}");
    assertThat(result).isEqualTo("http://host/callback");
  }

  @Test
  void passesNonStringValuesUnchanged() {
    Map<String, String> env = Map.of("CCD_DEF_BASE_URL", "replaced");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("Count", 42);
    row.put("Flag", Boolean.TRUE);
    row.put("Label", "${CCD_DEF_BASE_URL}");
    List<Map<String, Object>> result = Substitutor.injectEnvironmentVariables(env, List.of(row));
    assertThat(result).hasSize(1);
    assertThat(result.get(0).get("Count")).isEqualTo(42);
    assertThat(result.get(0).get("Flag")).isEqualTo(Boolean.TRUE);
    assertThat(result.get(0).get("Label")).isEqualTo("replaced");
  }

  @Test
  void doesNotMutateOriginalRows() {
    Map<String, String> env = Map.of("CCD_DEF_URL", "http://x");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("URL", "${CCD_DEF_URL}");
    List<Map<String, Object>> rows = List.of(row);
    Substitutor.injectEnvironmentVariables(env, rows);
    assertThat(row.get("URL")).isEqualTo("${CCD_DEF_URL}");
  }

  @Test
  void nullValuePassedThrough() {
    Map<String, String> env = Map.of("CCD_DEF_URL", "http://x");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("URL", null);
    List<Map<String, Object>> result = Substitutor.injectEnvironmentVariables(env, List.of(row));
    assertThat(result.get(0).get("URL")).isNull();
  }
}
