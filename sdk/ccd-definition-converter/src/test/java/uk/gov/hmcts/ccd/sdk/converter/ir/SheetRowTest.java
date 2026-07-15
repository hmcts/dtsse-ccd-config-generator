package uk.gov.hmcts.ccd.sdk.converter.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SheetRowTest {

  private SheetRow row(Map<String, Object> columns) {
    return SheetRow.builder()
        .sheet(SheetName.CASE_FIELD)
        .columns(columns)
        .overlayTags(Set.of())
        .source(Path.of("CaseField.json"))
        .build();
  }

  @Test
  void treatsAbsentNullAndBlankStringsUniformly() {
    SheetRow row = row(Map.of("ID", "  applicantName ", "Label", "   "));

    assertThat(row.getString("ID")).contains("applicantName");
    assertThat(row.getString("Label")).isEmpty();
    assertThat(row.getString("Missing")).isEmpty();
  }

  @Test
  void readsAliasedColumnsCanonicalFirst() {
    SheetRow both = row(Map.of("AccessProfile", "canonical", "UserRole", "legacy"));
    SheetRow legacyOnly = row(Map.of("UserRole", "legacy"));

    assertThat(both.getString("AccessProfile", "UserRole")).contains("canonical");
    assertThat(legacyOnly.getString("AccessProfile", "UserRole")).contains("legacy");
  }

  @Test
  void readsNumbersFromJsonNumbersAndStrings() {
    SheetRow row = row(Map.of("DisplayOrder", 3, "TabDisplayOrder", "7"));

    assertThat(row.getInteger("DisplayOrder")).contains(3);
    assertThat(row.getInteger("TabDisplayOrder")).contains(7);
  }

  @Test
  void readsIntegersFromEveryNumericRepresentation() {
    SheetRow row = row(Map.of(
        "IntColumn", 1, "StringColumn", "1", "DoubleColumn", 1.0d, "LongColumn", 1L));

    assertThat(row.getInteger("IntColumn")).contains(1);
    assertThat(row.getInteger("StringColumn")).contains(1);
    assertThat(row.getInteger("DoubleColumn")).contains(1);
    assertThat(row.getInteger("LongColumn")).contains(1);
  }

  @Test
  void rejectsFractionalAndNonNumericIntegerValuesLoudly() {
    SheetRow fractional = row(Map.of("DisplayOrder", "1.5"));
    SheetRow nonNumeric = row(Map.of("DisplayOrder", "abc"));

    assertThatThrownBy(() -> fractional.getInteger("DisplayOrder"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DisplayOrder")
        .hasMessageContaining("1.5");
    assertThatThrownBy(() -> nonNumeric.getInteger("DisplayOrder"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DisplayOrder")
        .hasMessageContaining("abc");
  }

  @Test
  void readsBooleanSpellingsTheDefinitionStoreAccepts() {
    SheetRow row = row(Map.of(
        "ShowSummary", "Y", "Publish", "No", "Searchable", "true",
        "Disabled", "F", "ReadOnly", "T"));

    assertThat(row.getYesNo("ShowSummary")).contains(true);
    assertThat(row.getYesNo("Publish")).contains(false);
    assertThat(row.getYesNo("Searchable")).contains(true);
    // RoleToAccessProfiles uses T/F spellings (e.g. ia-ccd-definitions Disabled).
    assertThat(row.getYesNo("Disabled")).contains(false);
    assertThat(row.getYesNo("ReadOnly")).contains(true);
    assertThat(row.getYesNo("Missing")).isEmpty();
  }

  @Test
  void rejectsNonBooleanValuesLoudly() {
    SheetRow row = row(Map.of("ShowSummary", "sometimes"));

    assertThatThrownBy(() -> row.getYesNo("ShowSummary"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ShowSummary")
        .hasMessageContaining("sometimes");
  }

  @Test
  void treatsUnresolvedEnvPlaceholderInBooleanColumnAsAbsent() {
    // sscs carries Publish=${CCD_DEF_PUBLISH} on WA-nonprod events; the value is resolved
    // per-environment at import time, so it is not a static boolean the converter can act on.
    SheetRow row = row(Map.of("Publish", "${CCD_DEF_PUBLISH}"));

    assertThat(row.getYesNo("Publish")).isEmpty();
  }
}
