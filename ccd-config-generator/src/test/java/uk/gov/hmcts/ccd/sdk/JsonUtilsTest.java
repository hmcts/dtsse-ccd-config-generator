package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.util.Lists;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.JsonUtils.OverwriteSpecific;

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
