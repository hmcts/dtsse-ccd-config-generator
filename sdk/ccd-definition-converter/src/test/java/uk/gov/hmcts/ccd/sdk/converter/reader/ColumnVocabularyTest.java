package uk.gov.hmcts.ccd.sdk.converter.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ColumnVocabularyTest {

  @Test
  void recognisesRealColumnsInEveryCasingTheImporterAccepts() {
    // ColumnName.equalsColumnNameOrAlias compares equalsIgnoreCase, so a definition's casing is
    // irrelevant to whether the value imports — and therefore to whether it may be dropped.
    assertThat(ColumnVocabulary.isKnown("ShowSummaryChangeOption")).isTrue();
    assertThat(ColumnVocabulary.isKnown("showsummarychangeoption")).isTrue();
    assertThat(ColumnVocabulary.isKnown("SearchPartyDoB")).isTrue();
    assertThat(ColumnVocabulary.isKnown("SearchPartyDOB")).isTrue();
    // The importer's declared alias for AccessProfile, and the two-stage keys
    // ccd-definition-processor's access-control-transformer expands before json2xlsx runs.
    assertThat(ColumnVocabulary.isKnown("UserRole")).isTrue();
    assertThat(ColumnVocabulary.isKnown("UserRoles")).isTrue();
    assertThat(ColumnVocabulary.isKnown("AccessControl")).isTrue();
  }

  @Test
  void rejectsKeysNoColumnNames() {
    // Each of these occurs in a real fixture and each imports as nothing.
    assertThat(ColumnVocabulary.isKnown(",ShowSummaryChangeOption")).isFalse();
    assertThat(ColumnVocabulary.isKnown("FieldShownCondition")).isFalse();
    assertThat(ColumnVocabulary.isKnown("PageShowShowCondition")).isFalse();
    assertThat(ColumnVocabulary.isKnown("retainHiddenValues")).isFalse();
    assertThat(ColumnVocabulary.isKnown("Definition")).isFalse();
    assertThat(ColumnVocabulary.isKnown("FieldLabel")).isFalse();
    assertThat(ColumnVocabulary.isKnown("FieldOrder")).isFalse();
    // A truncation of CallBackURLSubmittedEvent: prl's returnToPreviousState event has therefore
    // never had a submitted callback, which is the kind of finding the drop exists to surface.
    assertThat(ColumnVocabulary.isKnown("CallBackURLSubmitted")).isFalse();
    // A bare Retries names no callback phase, unlike RetriesTimeoutAboutToStartEvent.
    assertThat(ColumnVocabulary.isKnown("Retries")).isFalse();
    assertThat(ColumnVocabulary.isKnown(null)).isFalse();
  }

  @Test
  void rejectsTheOneTemplateHeaderTheImporterItselfIgnores() {
    // RetriesTimeoutURLPrintEvent is a header in ccd-template.xlsx but has no ColumnName constant:
    // CaseTypeParser.parsePrintWebhook attaches no timeouts, so the importer discards the cell even
    // though json2xlsx writes it. Being unknown here is correct — it is as inert as a typo, one step
    // later in the pipeline. (The CASE_TYPE_PRINT_RETRIES comparator rule still drops it from the
    // CaseType sheet, since that sheet's row reaches the comparator via a different path.)
    assertThat(ColumnVocabulary.isKnown("RetriesTimeoutURLPrintEvent")).isFalse();
  }

  @Test
  void separatesAuthorsDocumentationFromMistakes() {
    assertThat(ColumnVocabulary.isDocumentation("Comment")).isTrue();
    assertThat(ColumnVocabulary.isDocumentation("comment")).isTrue();
    assertThat(ColumnVocabulary.isDocumentation("Comments")).isTrue();
    assertThat(ColumnVocabulary.isDocumentation("_Comment")).isTrue();
    assertThat(ColumnVocabulary.isDocumentation("_Category")).isTrue();
    assertThat(ColumnVocabulary.isDocumentation("_Definition")).isTrue();
    assertThat(ColumnVocabulary.isDocumentation("comment_")).isTrue();

    // Not documentation — a column name written wrongly, which is worth reporting.
    assertThat(ColumnVocabulary.isDocumentation(",ShowSummaryChangeOption")).isFalse();
    assertThat(ColumnVocabulary.isDocumentation("FieldShownCondition")).isFalse();
    // Civil writes both _Definition (annotation) and Definition (an attempt at a column that does
    // not exist); only the underscored one is the documentation convention.
    assertThat(ColumnVocabulary.isDocumentation("Definition")).isFalse();
  }

  @Test
  void namesTheIntendedColumnOnlyWhenPunctuationIsTheWholeDifference() {
    assertThat(ColumnVocabulary.punctuationTypoOf(",ShowSummaryChangeOption"))
        .isEqualTo("ShowSummaryChangeOption");
    assertThat(ColumnVocabulary.punctuationTypoOf("FieldShowCondition:"))
        .isEqualTo("FieldShowCondition");

    // Misspellings of the name itself get no guess: over 119 columns a nearest-name search reads as
    // confident whether or not it is right, and nothing here distinguishes a misspelling from a key
    // that was never a column at all.
    assertThat(ColumnVocabulary.punctuationTypoOf("FieldShownCondition")).isNull();
    assertThat(ColumnVocabulary.punctuationTypoOf("PageShowShowCondition")).isNull();
    assertThat(ColumnVocabulary.punctuationTypoOf("CallBackURLSubmitted")).isNull();
    assertThat(ColumnVocabulary.punctuationTypoOf("_Comment")).isNull();
    // A column written correctly is not a typo of itself, whatever its own punctuation
    // (PreConditionState(s) contains parentheses that must survive stripping).
    assertThat(ColumnVocabulary.punctuationTypoOf("PreConditionState(s)")).isNull();
    assertThat(ColumnVocabulary.punctuationTypoOf("ShowSummaryChangeOption")).isNull();
  }

  @Test
  void listsUnknownKeysInRowOrderLeavingColumnsAlone() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("CaseEventID", "adjournCase");
    row.put(",ShowSummaryChangeOption", "Y");
    row.put("CaseFieldID", "adjournCaseTime");
    row.put("_Comment", "why");

    assertThat(ColumnVocabulary.unknownKeys(row))
        .containsExactly(",ShowSummaryChangeOption", "_Comment");
  }
}
