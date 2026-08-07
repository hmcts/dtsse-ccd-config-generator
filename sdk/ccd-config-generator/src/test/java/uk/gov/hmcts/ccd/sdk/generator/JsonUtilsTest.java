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
  public void aRealLabelDisplacesThePlaceholderWhicheverSideCarriesIt() {
    // Two model classes can share one CCD ID — the same simple name in different packages, or the
    // same @ComplexType(name) — and merge into a single ComplexTypes file. Plain AddMissing keeps
    // whatever the first-visited class wrote, so an unlabelled member blanked a labelled one's
    // ElementLabel down to the " " placeholder purely by winning the race (prl's two
    // DocumentDetails classes). The label must survive from either side.
    assertThat(mergeLabels(JsonUtils.DEFAULT_LABEL, "Document name"))
        .containsEntry("ElementLabel", "Document name");
    assertThat(mergeLabels("Document name", JsonUtils.DEFAULT_LABEL))
        .containsEntry("ElementLabel", "Document name");

    // Two real labels still disagree rather than silently merging: first writer wins, as before.
    assertThat(mergeLabels("Document name", "Uploaded date"))
        .containsEntry("ElementLabel", "Document name");
  }

  private Map<String, Object> mergeLabels(String existingLabel, String generatedLabel) {
    Map<String, Object> existing = Maps.newHashMap(Map.of(
        "ListElementCode", "documentName", "ElementLabel", existingLabel));
    Map<String, Object> generated = Maps.newHashMap(Map.of(
        "ListElementCode", "documentName", "ElementLabel", generatedLabel));

    return JsonUtils.mergeInto(Lists.newArrayList(existing), Lists.newArrayList(generated),
        new JsonUtils.AddMissingPreferringLabels(), "ListElementCode").get(0);
  }

}
