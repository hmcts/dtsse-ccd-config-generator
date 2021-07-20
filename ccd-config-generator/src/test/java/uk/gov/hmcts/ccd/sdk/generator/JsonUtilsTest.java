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

}
