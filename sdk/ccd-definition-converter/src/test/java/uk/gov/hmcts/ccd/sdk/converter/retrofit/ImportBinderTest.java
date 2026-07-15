package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ImportBinder}: the per-compilation-unit simple-name binder behind finding C1
 * (duplicate simple-name imports). A simple name free / already bound to the same type is written by
 * its simple name (registering an import once); a simple name already bound to a DIFFERENT type is
 * written fully-qualified with no clashing import added.
 */
class ImportBinderTest {

  @Test
  void bindsAFreeSimpleNameAndRegistersItsImport() {
    ImportBinder binder = new ImportBinder(new LinkedHashMap<>());
    assertThat(binder.reference("a.b.Foo")).isEqualTo("Foo");
    assertThat(binder.addedImports()).containsExactly("import a.b.Foo;");
  }

  @Test
  void reusesTheImportForASecondReferenceToTheSameType() {
    ImportBinder binder = new ImportBinder(new LinkedHashMap<>());
    assertThat(binder.reference("a.b.Foo")).isEqualTo("Foo");
    assertThat(binder.reference("a.b.Foo")).isEqualTo("Foo");
    assertThat(binder.addedImports()).containsExactly("import a.b.Foo;");
  }

  @Test
  void qualifiesAConflictWithAnExistingImportInsteadOfAddingADuplicate() {
    // The compilation unit already imports c.d.OtherDocuments; a synthesised field references a
    // DIFFERENT OtherDocuments (a.b.OtherDocuments) — it must be written fully-qualified with NO
    // second import (the prl BUG that produced "a type with the same simple name is already defined").
    Map<String, String> existing = new LinkedHashMap<>();
    existing.put("OtherDocuments", "c.d.OtherDocuments");
    ImportBinder binder = new ImportBinder(existing);
    assertThat(binder.reference("a.b.OtherDocuments")).isEqualTo("a.b.OtherDocuments");
    assertThat(binder.addedImports()).isEmpty();
    // The already-imported one still resolves by simple name and adds no import.
    assertThat(binder.reference("c.d.OtherDocuments")).isEqualTo("OtherDocuments");
    assertThat(binder.addedImports()).isEmpty();
  }

  @Test
  void qualifiesTheSecondOfTwoNewClashingTypes() {
    ImportBinder binder = new ImportBinder(new LinkedHashMap<>());
    assertThat(binder.reference("a.b.Document")).isEqualTo("Document");
    // A second, different Document must be fully-qualified (fpl's SDK type.Document vs model Document).
    assertThat(binder.reference("uk.gov.hmcts.ccd.sdk.type.Document"))
        .isEqualTo("uk.gov.hmcts.ccd.sdk.type.Document");
    assertThat(binder.addedImports()).containsExactly("import a.b.Document;");
  }
}
