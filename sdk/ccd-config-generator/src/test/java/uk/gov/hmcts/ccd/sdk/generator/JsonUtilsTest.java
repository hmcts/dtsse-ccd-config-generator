package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Maps;
import org.assertj.core.util.Lists;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.OverwriteSpecific;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class JsonUtilsTest {

  @Test
  public void setsOverwriteFields() {
    Map<String, Object> existing = Maps.newHashMap(Map.of(
        "id", "foo",
          "type", "int",
        "label", "bar" ));

    Map<String, Object> generated = Maps.newHashMap(Map.of(
        "id", "foo",
        "type", "string",
        "new", "value",
        "label", "baz" ));

    Map<String, Object> expected = Maps.newHashMap(Map.of(
        "id", "foo",
        "type", "string",
        "new", "value",
        "label", "bar" ));

    List<Map<String, Object>> result = JsonUtils
        .mergeInto(Lists.newArrayList(existing), Lists.newArrayList(generated),
            new OverwriteSpecific(Set.of("type")), "id");

    assertThat(result).containsExactly(expected);

  }

  @Test
  public void matchesAKeyPutWithANullValueOnlyAgainstAnotherNull() {
    Map<String, Object> unscoped = Maps.newHashMap();
    unscoped.put("name", "party");
    unscoped.put("collection", null);
    Map<String, Object> unscopedAgain = Maps.newHashMap(unscoped);
    Map<String, Object> scoped = Maps.newHashMap(Map.of("name", "party", "collection", "applicants"));

    List<Map<String, Object>> result = JsonUtils
        .mergeInto(Lists.newArrayList(unscoped), Lists.newArrayList(unscopedAgain, scoped),
            new JsonUtils.AddMissing(), "name", "collection");

    assertThat(result).hasSize(2);
  }

  @Test
  public void keepsRowsApartOnALaterKeyWhenAnEarlierKeyIsAbsentOnBoth() {
    Map<String, Object> first = Maps.newHashMap(Map.of("id", "applicant", "element", "firstName"));
    Map<String, Object> second = Maps.newHashMap(Map.of("id", "applicant", "element", "lastName"));

    List<Map<String, Object>> result = JsonUtils
        .mergeInto(Lists.newArrayList(first), Lists.newArrayList(second),
            new JsonUtils.AddMissing(), "id", "role", "element");

    assertThat(result).containsExactly(
        Map.of("id", "applicant", "element", "firstName"),
        Map.of("id", "applicant", "element", "lastName"));
  }

  @Test
  public void mergesRowsWhoseKeysAllMatchWithOneAbsentOnBoth() {
    Map<String, Object> existing = Maps.newHashMap(Map.of("id", "applicant", "element", "firstName"));
    Map<String, Object> generated = Maps.newHashMap(Map.of(
        "id", "applicant", "element", "firstName", "label", "First name"));

    List<Map<String, Object>> result = JsonUtils
        .mergeInto(Lists.newArrayList(existing), Lists.newArrayList(generated),
            new JsonUtils.AddMissing(), "id", "role", "element");

    assertThat(result).containsExactly(
        Map.of("id", "applicant", "element", "firstName", "label", "First name"));
  }

}
