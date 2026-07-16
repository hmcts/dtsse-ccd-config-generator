package uk.gov.hmcts.ccd.sdk.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NormalisingCcdConfigComparatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- KEY_ALIAS ----

    @Test
    public void userRoleIsTreatedAsAccessProfileOnAuthorisationSheets() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseEvent",
            rows(row("CaseEventID", "addNote", "UserRole", "caseworker", "CRUD", "CRU")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseEvent",
            rows(row("CaseEventID", "addNote", "AccessProfile", "caseworker", "CRUD", "CRU")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("KEY_ALIAS"));
    }

    @Test
    public void nameIsTreatedAsLabelOnCaseFieldRows() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "forename", "Name", "Forename", "FieldType", "Text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "forename", "Label", "Forename", "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void conflictingNameAndLabelStillFail() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "forename", "Name", "First name", "Label", "Forename", "FieldType", "Text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "forename", "Label", "Forename", "FieldType", "Text")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void caseTypeIdColumnCasingIsCanonicalised() {
        // The importer reads the case-type column case-insensitively (ET ships CaseTypeId on its
        // AuthorisationCaseField sheet and CaseTypeID on its CaseField sheet); the generator always
        // emits CaseTypeID, so the lower-case-d spelling is reconciled to the canonical one.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseTypeId", "ET_EnglandWales", "CaseFieldID", "note",
                "AccessProfile", "caseworker", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(row("CaseTypeID", "ET_EnglandWales", "CaseFieldID", "note",
                "AccessProfile", "caseworker", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("KEY_ALIAS"));
    }

    @Test
    public void differentCaseTypeIdStillFailsAcrossCasing() {
        // Canonicalising the column header must not mask a genuinely different case-type value.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseTypeId", "ET_EnglandWales", "CaseFieldID", "note",
                "AccessProfile", "caseworker", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(row("CaseTypeID", "ET_EnglandWales_Multiple", "CaseFieldID", "note",
                "AccessProfile", "caseworker", "CRUD", "R")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- LIVE_FROM ----

    @Test
    public void liveFromDifferencesAreIgnored() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "LiveFrom", "01/01/2019")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "LiveFrom", "01/01/2017")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("LIVE_FROM"));
    }

    @Test
    public void liveToDifferencesStillFail() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "LiveTo", "01/01/2030")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "LiveTo", "01/01/2031")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).anySatisfy(failure -> assertThat(failure).contains("LiveTo"));
    }

    // ---- LIVE_TO_VESTIGIAL ----

    @Test
    public void uniformVestigialLiveToOnAuthorisationCaseStateIsForgiven() {
        // Probate stamps an identical past-dated LiveTo on every AuthorisationCaseState row; the SDK
        // emits none. Because it is uniform across the whole sheet it is dead metadata, stripped.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseState",
            rows(
                row("CaseStateID", "Draft", "AccessProfile", "caseworker", "CRUD", "CRU",
                    "LiveTo", "01/01/2020"),
                row("CaseStateID", "Open", "AccessProfile", "caseworker", "CRUD", "R",
                    "LiveTo", "01/01/2020")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseState",
            rows(
                row("CaseStateID", "Draft", "AccessProfile", "caseworker", "CRUD", "CRU"),
                row("CaseStateID", "Open", "AccessProfile", "caseworker", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("LIVE_TO_VESTIGIAL"));
    }

    @Test
    public void perRowDivergentLiveToOnAuthorisationCaseStateStillFails() {
        // A staggered end-of-life (different LiveTo per row) is behavioural, not the uniform vestige,
        // so the column is left in place and the missing-on-actual row surfaces as a failure.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseState",
            rows(
                row("CaseStateID", "Draft", "AccessProfile", "caseworker", "CRUD", "CRU",
                    "LiveTo", "01/01/2020"),
                row("CaseStateID", "Open", "AccessProfile", "caseworker", "CRUD", "R",
                    "LiveTo", "01/01/2025")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseState",
            rows(
                row("CaseStateID", "Draft", "AccessProfile", "caseworker", "CRUD", "CRU"),
                row("CaseStateID", "Open", "AccessProfile", "caseworker", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).anySatisfy(failure -> assertThat(failure).contains("LiveTo"));
    }

    @Test
    public void vestigialLiveToIsNotForgivenOnOtherSheets() {
        // The rule is scoped to AuthorisationCaseState only; a uniform LiveTo on CaseEvent is still
        // behavioural (LiveFromRule leaves LiveTo alone), so it must fail.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "LiveTo", "01/01/2020")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).anySatisfy(failure -> assertThat(failure).contains("LiveTo"));
    }

    @Test
    public void liveToRoundTrippedOnBothSidesIsNotStripped() {
        // If the actual side carries a LiveTo, the value is compared normally: an equal value passes
        // and (guarded elsewhere) a divergent one fails — the rule must not fire and hide it.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseState",
            rows(row("CaseStateID", "Draft", "AccessProfile", "caseworker", "CRUD", "CRU",
                "LiveTo", "01/01/2020")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseState",
            rows(row("CaseStateID", "Draft", "AccessProfile", "caseworker", "CRUD", "CRU",
                "LiveTo", "01/01/2031")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).anySatisfy(failure -> assertThat(failure).contains("LiveTo"));
    }

    // ---- USER_PROFILE_EXCLUDED ----

    @Test
    public void userProfileSheetIsExcludedFromComparison() {
        // Maintainer decision 2026-07-16 (docs/userprofile-investigation.md): the sheet is
        // per-user/per-environment deployment data the SDK has no API for, so the comparator
        // drops it from both sides rather than let it recur as a residual in every fixture.
        Map<String, List<Map<String, Object>>> expected = sheets("UserProfile",
            rows(row("UserIDAMId", "nigel.dunne@solirius.com",
                "WorkBasketDefaultJurisdiction", "PROBATE",
                "WorkBasketDefaultCaseType", "GrantOfRepresentation",
                "WorkBasketDefaultState", "Open")));
        Map<String, List<Map<String, Object>>> actual = sheets("UserProfile", rows());

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("USER_PROFILE_EXCLUDED"));
    }

    @Test
    public void userProfileExclusionDoesNotTouchOtherSheets() {
        // The rule must be scoped to exactly the 'UserProfile' sheet: a genuine difference on any
        // other sheet in the same comparison still fails.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("UserProfile", rows(row("UserIDAMId", "someone@example.com",
            "WorkBasketDefaultJurisdiction", "PROBATE",
            "WorkBasketDefaultCaseType", "GrantOfRepresentation",
            "WorkBasketDefaultState", "Open")));
        expected.put("CaseEvent", rows(row("ID", "addNote", "Name", "Add note")));
        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseEvent", rows(row("ID", "addNote", "Name", "Added a note")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).anySatisfy(failure -> assertThat(failure).contains("'CaseEvent'"));
        assertThat(result.getFailures())
            .noneSatisfy(failure -> assertThat(failure).contains("'UserProfile'"));
    }

    // ---- CONFLICTING_ELEMENT_LABELS ----

    @Test
    public void conflictingElementLabelsCollapseToFirstSeen() {
        // prl declares a complex member twice (flat file + fragment) with differing ElementLabels;
        // the converter keeps the first-seen and emits one row. Collapsing the expected duplicates
        // to the first-seen label makes them exact duplicates that dedupe against the single row.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(
                row("ID", "PartyDetails", "ListElementCode", "firstName",
                    "FieldType", "Text", "ElementLabel", "First name"),
                row("ID", "PartyDetails", "ListElementCode", "firstName",
                    "FieldType", "Text", "ElementLabel", "Forename")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "PartyDetails", "ListElementCode", "firstName",
                "FieldType", "Text", "ElementLabel", "First name")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CONFLICTING_ELEMENT_LABELS"));
    }

    @Test
    public void singleDeclarationElementLabelDifferenceStillFails() {
        // Only one expected declaration: this is a genuine label difference, not a fragment conflict,
        // so the rule does not fire and the mismatch fails.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "PartyDetails", "ListElementCode", "firstName",
                "FieldType", "Text", "ElementLabel", "Forename")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "PartyDetails", "ListElementCode", "firstName",
                "FieldType", "Text", "ElementLabel", "First name")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("ElementLabel"));
    }

    @Test
    public void firstSeenLabelMustBeTheOneThatSurvivesForTheCollapseToMatch() {
        // The generated side carries the FIRST-seen label; if the actual holds the second label the
        // collapse (to first-seen) does not match it, so a wrong survivor still fails. Guards the
        // direction of the importer/converter first-seen citation.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(
                row("ID", "PartyDetails", "ListElementCode", "firstName",
                    "FieldType", "Text", "ElementLabel", "First name"),
                row("ID", "PartyDetails", "ListElementCode", "firstName",
                    "FieldType", "Text", "ElementLabel", "Forename")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "PartyDetails", "ListElementCode", "firstName",
                "FieldType", "Text", "ElementLabel", "Forename")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- DEFAULTS ----

    @Test
    public void generatorDefaultsAreForgivenWhenTheOtherSideOmitsTheColumn() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "SecurityClassification", "Public",
                "ShowSummary", "N",
                "ShowEventNotes", "N",
                "Publish", "N",
                "ShowSummaryChangeOption", "No",
                "Searchable", "Y",
                "EndButtonLabel", "Save and continue",
                "PostConditionState", "*")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("DEFAULTS"));
    }

    @Test
    public void nonDefaultValuesAreNotForgivenWhenTheOtherSideOmitsTheColumn() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "SecurityClassification", "Private")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("SecurityClassification").contains("PRIVATE"));
    }

    @Test
    public void searchableNonDefaultNotForgivenWhenOtherSideOmitsIt() {
        // Searchable=N is not the generator default, so an N present on only one side still fails.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text", "Searchable", "N")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("Searchable"));
    }

    @Test
    public void retainHiddenValueNoIsForgivenWhenTheOtherSideOmitsIt() {
        // The generator only ever emits RetainHiddenValue=Y; N/No is the CCD default (hidden values
        // not retained). A hand-written No where the generated side omits the column imports the
        // same. YnCanonRule canonicalises No→N before DEFAULTS runs.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "Respondent", "ListElementCode", "respondentFirstName",
                "FieldType", "Text", "RetainHiddenValue", "No")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "Respondent", "ListElementCode", "respondentFirstName",
                "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("DEFAULTS"));
    }

    @Test
    public void retainHiddenValueYesIsNotForgivenWhenTheOtherSideOmitsIt() {
        // Y is behavioural (hidden values ARE retained) and is exactly what the generator emits, so
        // a Y present on only one side is a genuine difference and must still fail.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "Respondent", "ListElementCode", "respondentFirstName",
                "FieldType", "Text", "RetainHiddenValue", "Yes")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "Respondent", "ListElementCode", "respondentFirstName",
                "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("RetainHiddenValue"));
    }

    @Test
    public void meaninglessColumnsAreRemovedUnconditionally() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "addNote", "CaseFieldID", "note",
                "Comment", "legacy comment",
                "PageFieldDisplayOrder", 3,
                "PageDisplayOrder", 1,
                "PageColumnNumber", 1,
                "PageLabel", " ")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "addNote", "CaseFieldID", "note",
                "PageFieldDisplayOrder", 7)));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void caseTypeIdIsStrippedFromComplexTypesRows() {
        // Complex types are jurisdiction-global; the importer ignores a case-type column on the
        // ComplexTypes sheet and the generator's ComplexTypeGenerator removes it. A hand-written
        // CaseTypeID (civil, prl) is dead metadata there.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "CaseManagementCategoryElement", "ListElementCode", "code",
                "FieldType", "Text", "CaseTypeID", "CIVIL")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "CaseManagementCategoryElement", "ListElementCode", "code",
                "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void caseTypeIdIsNotStrippedFromAuthorisationRows() {
        // The strip is scoped to ComplexTypes only; on other sheets CaseTypeID is a real key.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseTypeID", "CIVIL", "CaseFieldID", "note",
                "AccessProfile", "caseworker", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(row("CaseTypeID", "CIVIL_OTHER", "CaseFieldID", "note",
                "AccessProfile", "caseworker", "CRUD", "R")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void underscorePrefixedAnnotationColumnsAreRemovedUnconditionally() {
        // Columns whose header starts with '_' are inline documentation the importer ignores (civil
        // ships _Comment/_Category/_Definition holding prose); they never reach the generated side.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text",
                "_Comment", "Label for legal rep view",
                "_Category", "party",
                "_Definition", "free text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void displayContextParameterIsStrippedFromTheCaseFieldSheet() {
        // The importer's CaseFieldParser never reads DisplayContextParameter on the CaseField sheet
        // (DCP is a per-page property, read on CaseEventToFields/ComplexTypes), and the SDK has no
        // CaseField-level DCP API, so a CaseField-row DCP is importer-ignored metadata.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "docs", "Label", "Docs", "FieldType", "Collection",
                "DisplayContextParameter", "#COLLECTION(allowInsert,allowDelete)")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "docs", "Label", "Docs", "FieldType", "Collection")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void displayContextParameterIsNotStrippedFromOtherSheets() {
        // The strip is scoped to the CaseField sheet; on CaseEventToFields the importer DOES read
        // DisplayContextParameter, so a value present on only one side must still fail.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "docs",
                "DisplayContextParameter", "#COLLECTION(allowInsert)")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "docs")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("DisplayContextParameter"));
    }

    // ---- SECURITY_CLASSIFICATION_CASE ----

    @Test
    public void securityClassificationCaseDifferenceIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text",
                "SecurityClassification", "PUBLIC")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text",
                "SecurityClassification", "Public")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("SECURITY_CLASSIFICATION_CASE"));
    }

    @Test
    public void genuinelyDifferentSecurityClassificationStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text",
                "SecurityClassification", "PRIVATE")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "note", "Label", "Note", "FieldType", "Text",
                "SecurityClassification", "Public")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("SecurityClassification"));
    }

    // ---- YN_CANON ----

    @Test
    public void booleanIshStringsAreCanonicalisedOnYnColumns() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "ShowSummary", "Yes", "ShowEventNotes", "false")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "ShowSummary", "Y", "ShowEventNotes", "N")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("YN_CANON"));
    }

    @Test
    public void tfSpellingsAreCanonicalisedOnRoleToAccessProfileFlags() {
        Map<String, List<Map<String, Object>>> expected = sheets("RoleToAccessProfiles",
            rows(row("RoleName", "citizen", "Disabled", "F", "ReadOnly", "T")));
        Map<String, List<Map<String, Object>>> actual = sheets("RoleToAccessProfiles",
            rows(row("RoleName", "citizen", "Disabled", "N", "ReadOnly", "Y")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("YN_CANON"));
    }

    @Test
    public void capitalisedBooleanSpellingsAreCanonicalisedOnYnColumns() {
        // prl writes Publish as "True"/"False" (capital); the importer parses these
        // case-insensitively, so they canonicalise to the generator's Y/N.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "Publish", "True")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "Publish", "Y")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("YN_CANON"));
    }

    @Test
    public void nonYnColumnsAreNotCanonicalised() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "flag", "Label", "Yes", "FieldType", "Text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "flag", "Label", "Y", "FieldType", "Text")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- Callback URLs are compared exactly (no CALLBACK_URL rule) ----

    @Test
    public void identicalCallbackUrlsMatchExactly() {
        // The converter carries callback URLs through verbatim, so both sides hold the same raw
        // value (env ${CCD_DEF_*} placeholders included) and compare like any other column.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToSubmitEvent", "${CCD_DEF_BASE_URL}/callbacks/about-to-submit",
                "RetriesTimeoutURLAboutToSubmitEvent", "45,60,90")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToSubmitEvent", "${CCD_DEF_BASE_URL}/callbacks/about-to-submit",
                "RetriesTimeoutURLAboutToSubmitEvent", "45,60,90")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void differingCallbackUrlShapeNowFails() {
        // With CALLBACK_URL retired, a differing URL shape is a real diff — presence is no longer
        // enough. (The converter carries the value verbatim, so a divergence here means the graft
        // failed to reproduce the original.)
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToSubmitEvent", "http://legacy-host:4013/callbacks/about-to-submit")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToSubmitEvent", "${CASE_API_URL}/callbacks/about-to-submit")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void callbackPresentOnOneSideOnlyStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLSubmittedEvent", "http://legacy-host:4013/callbacks/submitted")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("CallBackURLSubmittedEvent"));
    }

    // ---- CASE_EVENT_RETRIES ----

    @Test
    public void aboutToStartRetriesColumnNameFormIsReconciled() {
        // The callback URL is identical on both sides (carried verbatim); only the retry column's
        // name form (plain vs URL spelling) differs, which is what CASE_EVENT_RETRIES reconciles.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToStartEvent", "${CASE_API_URL}/callbacks/about-to-start",
                "RetriesTimeoutAboutToStartEvent", "5,5,5,5")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToStartEvent", "${CASE_API_URL}/callbacks/about-to-start",
                "RetriesTimeoutURLAboutToStartEvent", "5,5,5,5")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CASE_EVENT_RETRIES"));
    }

    @Test
    public void retriesWithoutACallbackAreDropped() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "RetriesTimeoutURLAboutToSubmitEvent", "5,5,5,5")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CASE_EVENT_RETRIES"));
    }

    @Test
    public void differingRetriesWithACallbackStillFail() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToSubmitEvent", "${CASE_API_URL}/callbacks/about-to-submit",
                "RetriesTimeoutURLAboutToSubmitEvent", "5,5,5,5")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note",
                "CallBackURLAboutToSubmitEvent", "${CASE_API_URL}/callbacks/about-to-submit",
                "RetriesTimeoutURLAboutToSubmitEvent", "9,9")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- CASE_HISTORY ----

    @Test
    public void generatorInjectedCaseHistoryRowsAreForgiven() {
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "note", "Label", "Note", "FieldType", "Text")));
        expected.put("CaseTypeTab", rows(
            row("TabID", "notes", "CaseFieldID", "note", "TabLabel", "Notes")));

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(
            row("ID", "note", "Label", "Note", "FieldType", "Text"),
            row("ID", "caseHistory", "Label", "History", "FieldType", "CaseHistoryViewer")));
        actual.put("CaseTypeTab", rows(
            row("TabID", "notes", "CaseFieldID", "note", "TabLabel", "Notes"),
            row("TabID", "history", "CaseFieldID", "caseHistory", "TabLabel", "History")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .filteredOn(rule -> rule.startsWith("CASE_HISTORY"))
            .hasSize(2);
    }

    @Test
    public void injectedCaseHistoryCruAuthGrantsAreForgivenPerRole() {
        // The generator injects caseHistory=CRU for every role holding a field grant. The input
        // declares caseHistory only for caseworker (at the narrower R); the extra injected roles
        // and the R->CRU widening on caseworker are both forgiven, but nothing wider than CRU is.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField", rows(
            row("CaseFieldID", "caseHistory", "UserRole", "caseworker", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField", rows(
            row("CaseFieldID", "caseHistory", "AccessProfile", "caseworker", "CRUD", "CRU"),
            row("CaseFieldID", "caseHistory", "AccessProfile", "judge", "CRUD", "CRU")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CASE_HISTORY"));
    }

    @Test
    public void caseHistoryAuthGrantWiderThanCruStillFails() {
        // Actual holds D on caseHistory — broader than the CRU the generator injects — so it is a
        // real difference and must not be forgiven.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField", rows(
            row("CaseFieldID", "caseHistory", "UserRole", "caseworker", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField", rows(
            row("CaseFieldID", "caseHistory", "AccessProfile", "caseworker", "CRUD", "CRUD")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
    }

    @Test
    public void caseHistoryRowsAreComparedNormallyWhenExpectedDeclaresThem() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "caseHistory", "Label", "History", "FieldType", "CaseHistoryViewer")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "caseHistory", "Label", "Case history", "FieldType", "CaseHistoryViewer")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).anySatisfy(failure -> assertThat(failure).contains("Label"));
    }

    // ---- NUMERIC_STRINGS ----

    @Test
    public void numbersAndEqualNumericStringsAreEquivalent() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "addNote", "CaseFieldID", "note", "PageID", 1)));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "addNote", "CaseFieldID", "note", "PageID", "1")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).anySatisfy(rule -> assertThat(rule).startsWith("NUMERIC_STRINGS"));
    }

    @Test
    public void differentNumericValuesStillFail() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "addNote", "CaseFieldID", "note", "PageID", 1)));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "addNote", "CaseFieldID", "note", "PageID", "2")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- EMPTY_STRING_ABSENT ----

    @Test
    public void blankStringIsTreatedAsAbsentColumn() {
        // DisplayContextParameter is neither role-aliased nor otherwise normalised, so a blank
        // value versus an absent one exercises EMPTY_STRING_ABSENT in isolation.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(row("TabID", "summary", "CaseFieldID", "note")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(row("TabID", "summary", "CaseFieldID", "note", "DisplayContextParameter", "")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("EMPTY_STRING_ABSENT"));
    }

    @Test
    public void nonBlankValueMissingOnOtherSideStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(row("TabID", "summary", "CaseFieldID", "note")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(row("TabID", "summary", "CaseFieldID", "note", "UserRole", "caseworker")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void emptyStringAndNullOnBothSidesAreEquivalent() {
        // The generator emits Categories.ParentCategoryID as an explicit null where the definition
        // carries an empty string; both mean "no parent" to the importer.
        Map<String, List<Map<String, Object>>> expected = sheets("Categories",
            rows(row("CategoryID", "C1", "CategoryLabel", "Starting a Claim",
                "ParentCategoryID", "")));
        Map<String, List<Map<String, Object>>> actual = sheets("Categories",
            rows(row("CategoryID", "C1", "CategoryLabel", "Starting a Claim",
                "ParentCategoryID", null)));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("EMPTY_STRING_ABSENT"));
    }

    @Test
    public void blankValueAgainstNonBlankOnBothSidesStillFails() {
        // Both sides carry the column, but only one is blank; that is a genuine difference.
        Map<String, List<Map<String, Object>>> expected = sheets("Categories",
            rows(row("CategoryID", "C2", "CategoryLabel", "Response",
                "ParentCategoryID", "")));
        Map<String, List<Map<String, Object>>> actual = sheets("Categories",
            rows(row("CategoryID", "C2", "CategoryLabel", "Response",
                "ParentCategoryID", "C1")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- duplicate-row collapse (blank-vs-absent tolerant) ----

    @Test
    public void duplicateExpectedRowsDifferingOnlyByBlankVsAbsentColumnCollapseToOne() {
        // prl ships the same EventToComplexTypes row from both a flat CaseEventToComplexTypes.json
        // file and a CaseEventToComplexTypes/ fragment directory; one copy carries an empty
        // EventElementLabel, the other omits the column entirely. Both import identically (see
        // EMPTY_STRING_ABSENT), so the duplicate-collapse must tolerate that difference too,
        // rather than treating them as a genuine same-key content conflict.
        Map<String, List<Map<String, Object>>> expected = sheets("EventToComplexTypes",
            rows(
                row("ID", "Child", "CaseEventID", "childDetails", "CaseFieldID", "children",
                    "ListElementCode", "firstName", "EventElementLabel", "", "DisplayContext", "OPTIONAL"),
                row("ID", "Child", "CaseEventID", "childDetails", "CaseFieldID", "children",
                    "ListElementCode", "firstName", "DisplayContext", "OPTIONAL")));
        Map<String, List<Map<String, Object>>> actual = sheets("EventToComplexTypes",
            rows(row("ID", "Child", "CaseEventID", "childDetails", "CaseFieldID", "children",
                "ListElementCode", "firstName", "DisplayContext", "OPTIONAL")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void duplicateExpectedRowsWithGenuinelyConflictingColumnStillFail() {
        // Same key, but the DisplayContext values themselves disagree (not merely blank-vs-absent)
        // — a real same-key content conflict must remain visible, not collapse away.
        Map<String, List<Map<String, Object>>> expected = sheets("EventToComplexTypes",
            rows(
                row("ID", "Child", "CaseEventID", "childDetails", "CaseFieldID", "children",
                    "ListElementCode", "firstName", "DisplayContext", "OPTIONAL"),
                row("ID", "Child", "CaseEventID", "childDetails", "CaseFieldID", "children",
                    "ListElementCode", "firstName", "DisplayContext", "MANDATORY")));
        Map<String, List<Map<String, Object>>> actual = sheets("EventToComplexTypes",
            rows(row("ID", "Child", "CaseEventID", "childDetails", "CaseFieldID", "children",
                "ListElementCode", "firstName", "DisplayContext", "OPTIONAL")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
    }

    // ---- STATE_DESCRIPTION ----

    @Test
    public void descriptionDefaultedToNameIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("State",
            rows(row("ID", "Open", "Name", "Open")));
        Map<String, List<Map<String, Object>>> actual = sheets("State",
            rows(row("ID", "Open", "Name", "Open", "Description", "Open")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("STATE_DESCRIPTION"));
    }

    @Test
    public void eventDescriptionDefaultedToNameIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "Name", "Add note", "Description", "Add note")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("STATE_DESCRIPTION"));
    }

    @Test
    public void descriptionDifferingFromNameStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("State",
            rows(row("ID", "Open", "Name", "Open")));
        Map<String, List<Map<String, Object>>> actual = sheets("State",
            rows(row("ID", "Open", "Name", "Open", "Description", "Case is open")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- FIELD_TYPE_COMPLEX ----

    @Test
    public void inferredComplexFieldTypeIsCanonicalisedToComplexPlusParameter() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "respondent", "Label", "Respondent",
                "FieldType", "Complex", "FieldTypeParameter", "Party")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "respondent", "Label", "Respondent", "FieldType", "Party")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("FIELD_TYPE_COMPLEX"));
    }

    @Test
    public void baseFieldTypeIsNeverTreatedAsComplex() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "notes", "Label", "Notes",
                "FieldType", "Complex", "FieldTypeParameter", "TextArea")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "notes", "Label", "Notes", "FieldType", "TextArea")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void genuinelyDifferentComplexTypeParameterStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "respondent", "Label", "Respondent",
                "FieldType", "Complex", "FieldTypeParameter", "Party")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "respondent", "Label", "Respondent", "FieldType", "Applicant")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- PUBLISH_IGNORED_ON_FIELD_SHEETS ----

    @Test
    public void publishIsStrippedFromTheCaseFieldSheet() {
        // CaseFieldParser never reads ColumnName.PUBLISH and CaseFieldEntity has no publish field;
        // Publish is a CaseEventToFields/EventToComplexTypes-only concept.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "docs", "Label", "Docs", "FieldType", "Text", "Publish", "Y")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "docs", "Label", "Docs", "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("PUBLISH_IGNORED_ON_FIELD_SHEETS"));
    }

    @Test
    public void publishIsStrippedFromTheComplexTypesSheet() {
        // ComplexFieldTypeParser.parseComplexField never reads ColumnName.PUBLISH either; the
        // per-nested-field Publish concept lives on EventToComplexTypes, not ComplexTypes.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "Applicant", "ListElementCode", "name", "Label", "Name",
                "FieldType", "Text", "Publish", "Y")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "Applicant", "ListElementCode", "name", "Label", "Name",
                "FieldType", "Text")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void publishIsNotStrippedFromCaseEventToFields() {
        // The strip is scoped to CaseField/ComplexTypes; on CaseEventToFields the importer DOES
        // read Publish (EventCaseFieldParser), so a value present on only one side still fails.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "docs", "Publish", "Y")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "docs")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("Publish"));
    }

    // ---- POST_CONDITION_NO_CHANGE ----

    @Test
    public void wildcardPostConditionMatchingSinglePreStateIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "PreConditionState(s)", "Open", "PostConditionState", "*")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "PreConditionState(s)", "Open", "PostConditionState", "Open")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("POST_CONDITION_NO_CHANGE"));
    }

    @Test
    public void wildcardPostConditionWithDifferentConcreteStateStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "PreConditionState(s)", "Open", "PostConditionState", "*")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "PreConditionState(s)", "Open", "PostConditionState", "Closed")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- CONDITIONAL_POST_STATE ----

    @Test
    public void conditionalPostStateCollapsingToPrimaryIsForgiven() {
        // Expected carries a conditional grammar state(cond):priority;fallback; the SDK emits only
        // the first token's primary state. The accepted-semantic-difference rule forgives it.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "startAppeal", "PostConditionState",
                "appealStartedByAdmin(isAdmin=\"Yes\"):2;appealStarted")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "startAppeal", "PostConditionState", "appealStartedByAdmin")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CONDITIONAL_POST_STATE"));
    }

    @Test
    public void multiTargetPostStateWithoutConditionCollapsingToFirstIsForgiven() {
        // A ;-separated multi-target with no parens: primary is the first token.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "decide", "PostConditionState", "Accepted;Rejected")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "decide", "PostConditionState", "Accepted")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void conditionalPostStateWhosePrimaryDisagreesStillFails() {
        // If the generated primary is NOT the first token's state, the collapse is not the accepted
        // one and the diff must still fail (guards against a generator regression).
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "decide", "PostConditionState", "Accepted(cond):1;Rejected")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "decide", "PostConditionState", "Rejected")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void reverseShapeGeneratedConditionalNotInInputStillFails() {
        // The rule never touches the actual side: a plain expected vs a conditional actual must fail.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "decide", "PostConditionState", "Accepted")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "decide", "PostConditionState", "Accepted(cond):1;Rejected")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- PRE_CONDITION_STATE_ORDER ----

    @Test
    public void preConditionStatesAreComparedAsAnUnorderedSet() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "sendDirection", "PreConditionState(s)", "caseBuilding;appealSubmitted;listing")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "sendDirection", "PreConditionState(s)", "appealSubmitted;caseBuilding;listing")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("PRE_CONDITION_STATE_ORDER"));
    }

    @Test
    public void differentPreConditionStateSetsStillFail() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "sendDirection", "PreConditionState(s)", "caseBuilding;appealSubmitted")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "sendDirection", "PreConditionState(s)", "appealSubmitted;listing")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- PAGE_LABEL_PROPAGATION ----

    @Test
    public void pageLabelSetOnlyOnFirstFieldIsPropagatedAcrossThePage() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "createCase", "CaseFieldID", "name", "PageID", "1",
                    "PageLabel", "Applicant details"),
                row("CaseEventID", "createCase", "CaseFieldID", "email", "PageID", "1")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "createCase", "CaseFieldID", "name", "PageID", "1",
                    "PageLabel", "Applicant details"),
                row("CaseEventID", "createCase", "CaseFieldID", "email", "PageID", "1",
                    "PageLabel", "Applicant details")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("PAGE_LABEL_PROPAGATION"));
    }

    @Test
    public void midEventCallbackRoundTripsPerRowWithoutPropagation() {
        // The converter emits no SDK mid-event wiring; it carries the input's CallBackURLMidEvent /
        // RetriesTimeoutURLMidEvent through verbatim per field row (the CaseEventToFields column
        // graft), so both sides hold the value on the SAME field row and compare directly. It is no
        // longer propagated across the page (PageLabel/PageShowCondition still are), so the value
        // stays on its own row rather than being spread to every row of the page.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "startAppeal", "CaseFieldID", "a", "PageID", "1"),
                row("CaseEventID", "startAppeal", "CaseFieldID", "b", "PageID", "1",
                    "CallBackURLMidEvent", "${CASE_API_URL}/mid", "RetriesTimeoutURLMidEvent", "5,5")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "startAppeal", "CaseFieldID", "a", "PageID", "1"),
                row("CaseEventID", "startAppeal", "CaseFieldID", "b", "PageID", "1",
                    "CallBackURLMidEvent", "${CASE_API_URL}/mid", "RetriesTimeoutURLMidEvent", "5,5")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void midEventCallbackOnADifferentFieldRowNoLongerReconciles() {
        // Guards the design change: because mid-event columns are NOT propagated across the page,
        // the same URL placed on a different field row on each side is a genuine per-row difference
        // and fails. (Before, PageLabelPropagation would have spread it to every row and matched.)
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "startAppeal", "CaseFieldID", "a", "PageID", "1"),
                row("CaseEventID", "startAppeal", "CaseFieldID", "b", "PageID", "1",
                    "CallBackURLMidEvent", "${CASE_API_URL}/mid")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "startAppeal", "CaseFieldID", "a", "PageID", "1",
                    "CallBackURLMidEvent", "${CASE_API_URL}/mid"),
                row("CaseEventID", "startAppeal", "CaseFieldID", "b", "PageID", "1")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void differentPageLabelsOnTheSamePageStillFail() {
        // The page label is page-scoped (CCD renders the page's first-field label for the whole
        // page), so per-field variation within one side is collapsed to that first-field label.
        // A genuine difference on the page's *first* field is therefore what must still fail.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "createCase", "CaseFieldID", "name", "PageID", "1",
                    "PageLabel", "Applicant details"),
                row("CaseEventID", "createCase", "CaseFieldID", "email", "PageID", "1")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "createCase", "CaseFieldID", "name", "PageID", "1",
                    "PageLabel", "A genuinely different page label"),
                row("CaseEventID", "createCase", "CaseFieldID", "email", "PageID", "1")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void perFieldPageLabelVariationWithinAPageIsCollapsed() {
        // One side legitimately carries a different PageLabel on each field of a page; since CCD
        // uses the page's first-field label, both sides collapse to it and match.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "createCase", "CaseFieldID", "name", "PageID", "1",
                    "PageLabel", "Applicant details"),
                row("CaseEventID", "createCase", "CaseFieldID", "email", "PageID", "1",
                    "PageLabel", "Contact details")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(
                row("CaseEventID", "createCase", "CaseFieldID", "name", "PageID", "1",
                    "PageLabel", "Applicant details"),
                row("CaseEventID", "createCase", "CaseFieldID", "email", "PageID", "1",
                    "PageLabel", "Applicant details")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isTrue();
    }

    // ---- CASE_TYPE_TAB ----

    @Test
    public void caseTypeTabChannelLabelAndOrderingAreReconciled() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(
                row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview",
                    "TabFieldDisplayOrder", 1),
                row("TabID", "overview", "CaseFieldID", "b", "TabFieldDisplayOrder", 4)));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(
                row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview",
                    "Channel", "CaseWorker", "TabFieldDisplayOrder", 1),
                row("TabID", "overview", "CaseFieldID", "b", "TabLabel", "Overview",
                    "Channel", "CaseWorker", "TabFieldDisplayOrder", 2)));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CASE_TYPE_TAB"));
    }

    @Test
    public void caseTypeTabNonDefaultChannelStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview",
                "Channel", "CitizenOnly")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("Channel"));
    }

    @Test
    public void caseTypeTabConflictingTabLabelStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Different")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void caseTypeTabDifferingTabLabelOnNonFirstRowIsCollapsedToFirstRowValue() {
        // The importer (AbstractDisplayGroupParser.parseGroup) reads a tab's TabLabel from only
        // the group's first row; a hand-written definition may still carry a different (stale or
        // heading-style) TabLabel on a later row of the same tab, which the importer never reads.
        // civil's ClaimDetails tab and prl's confidentialDetails tab both ship this shape.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(
                row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview"),
                row("TabID", "overview", "CaseFieldID", "b", "TabLabel", "Overview details")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(
                row("TabID", "overview", "CaseFieldID", "a", "TabLabel", "Overview"),
                row("TabID", "overview", "CaseFieldID", "b", "TabLabel", "Overview")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CASE_TYPE_TAB"));
    }

    // ---- TAB_READ_INJECTION ----

    @Test
    public void generatorInjectedReadOnlyTabGrantIsForgivenWhenRoleHasOtherGrants() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "name", "AccessProfile", "citizen", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(
                row("CaseFieldID", "name", "AccessProfile", "citizen", "CRUD", "R"),
                row("CaseFieldID", "notes", "AccessProfile", "citizen", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("TAB_READ_INJECTION"));
    }

    @Test
    public void extraGrantForRoleWithNoOtherGrantsStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "name", "AccessProfile", "caseworker", "CRUD", "CRUD")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(
                row("CaseFieldID", "name", "AccessProfile", "caseworker", "CRUD", "CRUD"),
                row("CaseFieldID", "notes", "AccessProfile", "citizen", "CRUD", "R")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void extraGrantWithNonReadOnlyCrudStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "name", "AccessProfile", "citizen", "CRUD", "R")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(
                row("CaseFieldID", "name", "AccessProfile", "citizen", "CRUD", "R"),
                row("CaseFieldID", "notes", "AccessProfile", "citizen", "CRUD", "CRU")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- IMMUTABLE_FIELD_CR (maintainer-accepted difference) ----

    @Test
    public void crInjectionOnLabelFieldIsForgivenWhenExpectedOmitsTheRow() {
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "pageTitle", "Label", "Title", "FieldType", "Label")));
        expected.put("AuthorisationCaseField", rows());

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "pageTitle", "Label", "Title", "FieldType", "Label")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "pageTitle", "UserRole", "caseworker", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("IMMUTABLE_FIELD_CR"));
    }

    @Test
    public void crInjectionOnLabelFieldIsForgivenWhenExpectedGrantsBareR() {
        // Input grants R; SDK widens to CR on the immutable field. Surplus {C} ⊆ {C,R}.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "pageTitle", "Label", "Title", "FieldType", "Label")));
        expected.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "pageTitle", "UserRole", "caseworker", "CRUD", "R")));

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "pageTitle", "Label", "Title", "FieldType", "Label")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "pageTitle", "UserRole", "caseworker", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("IMMUTABLE_FIELD_CR"));
    }

    @Test
    public void crInjectionOnAlwaysReadonlyFieldIsForgiven() {
        // Non-Label field, but READONLY on every CaseEventToFields occurrence → immutable.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "roDesc", "Label", "Desc", "FieldType", "Text")));
        expected.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "roDesc", "DisplayContext", "READONLY")));
        expected.put("AuthorisationCaseField", rows());

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "roDesc", "Label", "Desc", "FieldType", "Text")));
        actual.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "roDesc", "DisplayContext", "READONLY")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "roDesc", "UserRole", "caseworker", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("IMMUTABLE_FIELD_CR"));
    }

    @Test
    public void crInjectionOnEditableFieldStillFails() {
        // Ordinary editable field (not Label, not always-READONLY): extra grant is a real diff.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "name", "Label", "Name", "FieldType", "Text")));
        expected.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "name", "DisplayContext", "MANDATORY")));
        expected.put("AuthorisationCaseField", rows());

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "name", "Label", "Name", "FieldType", "Text")));
        actual.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "name", "DisplayContext", "MANDATORY")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "name", "UserRole", "caseworker", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("name"));
    }

    @Test
    public void surplusBeyondCrOnImmutableFieldStillFails() {
        // Surplus contains U — that is broader than CR injection and must still fail.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "pageTitle", "Label", "Title", "FieldType", "Label")));
        expected.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "pageTitle", "UserRole", "caseworker", "CRUD", "R")));

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "pageTitle", "Label", "Title", "FieldType", "Label")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "pageTitle", "UserRole", "caseworker", "CRUD", "CRU")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("CRUD"));
    }

    @Test
    public void fieldReadonlyOnSomeEventsButEditableOnOthersIsNotImmutable() {
        // Editable on any event → not immutable → CR injection not forgiven.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "mixed", "Label", "Mixed", "FieldType", "Text")));
        expected.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "mixed", "DisplayContext", "READONLY"),
            row("CaseEventID", "e2", "CaseFieldID", "mixed", "DisplayContext", "MANDATORY")));
        expected.put("AuthorisationCaseField", rows());

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "mixed", "Label", "Mixed", "FieldType", "Text")));
        actual.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "mixed", "DisplayContext", "READONLY"),
            row("CaseEventID", "e2", "CaseFieldID", "mixed", "DisplayContext", "MANDATORY")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "mixed", "UserRole", "caseworker", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
    }

    @Test
    public void crInjectionForgivenForRoleGrantedOnlyOnReadonlyEvent() {
        // 'mixed' is editable on e2 but READONLY on e1; only e1 grants iacjudge. The SDK builds the
        // field immutable on e1 and injects CR for (mixed, iacjudge) even though the input grants R.
        // The role-scoped immutable path forgives that surplus for iacjudge but nothing wider.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "mixed", "Label", "Mixed", "FieldType", "Text")));
        expected.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "mixed", "DisplayContext", "READONLY"),
            row("CaseEventID", "e2", "CaseFieldID", "mixed", "DisplayContext", "MANDATORY")));
        expected.put("AuthorisationCaseEvent", rows(
            row("CaseEventID", "e1", "UserRole", "iacjudge", "CRUD", "R")));
        expected.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "mixed", "UserRole", "iacjudge", "CRUD", "R")));

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "mixed", "Label", "Mixed", "FieldType", "Text")));
        actual.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "mixed", "DisplayContext", "READONLY"),
            row("CaseEventID", "e2", "CaseFieldID", "mixed", "DisplayContext", "MANDATORY")));
        actual.put("AuthorisationCaseEvent", rows(
            row("CaseEventID", "e1", "AccessProfile", "iacjudge", "CRUD", "R")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "mixed", "AccessProfile", "iacjudge", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("IMMUTABLE_FIELD_CR"));
    }

    @Test
    public void crInjectionNotForgivenForRoleNotGrantedOnReadonlyEvent() {
        // 'mixed' is READONLY only on e1, which grants iacjudge — NOT caseofficer (granted on e2,
        // where the field is editable). A CR surplus for caseofficer is therefore not explained by
        // immutable-field injection and must still fail.
        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        expected.put("CaseField", rows(row("ID", "mixed", "Label", "Mixed", "FieldType", "Text")));
        expected.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "mixed", "DisplayContext", "READONLY"),
            row("CaseEventID", "e2", "CaseFieldID", "mixed", "DisplayContext", "MANDATORY")));
        expected.put("AuthorisationCaseEvent", rows(
            row("CaseEventID", "e1", "UserRole", "iacjudge", "CRUD", "R"),
            row("CaseEventID", "e2", "UserRole", "caseofficer", "CRUD", "CRU")));
        expected.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "mixed", "UserRole", "caseofficer", "CRUD", "R")));

        Map<String, List<Map<String, Object>>> actual = new LinkedHashMap<>();
        actual.put("CaseField", rows(row("ID", "mixed", "Label", "Mixed", "FieldType", "Text")));
        actual.put("CaseEventToFields", rows(
            row("CaseEventID", "e1", "CaseFieldID", "mixed", "DisplayContext", "READONLY"),
            row("CaseEventID", "e2", "CaseFieldID", "mixed", "DisplayContext", "MANDATORY")));
        actual.put("AuthorisationCaseEvent", rows(
            row("CaseEventID", "e1", "AccessProfile", "iacjudge", "CRUD", "R"),
            row("CaseEventID", "e2", "AccessProfile", "caseofficer", "CRUD", "CRU")));
        actual.put("AuthorisationCaseField", rows(
            row("CaseFieldID", "mixed", "AccessProfile", "caseofficer", "CRUD", "CR")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
    }

    // ---- Aggregation ----

    @Test
    public void aggregatesFragmentedGeneratedOutputIntoLogicalSheets() throws IOException {
        File root = tmp.getRoot();
        write(root, "CaseEvent/addNote.json", "[{\"ID\": \"addNote\"}]");
        write(root, "CaseEvent/closeCase.json", "[{\"ID\": \"closeCase\"}]");
        write(root, "CaseEventToFields/addNote.json",
            "[{\"CaseEventID\": \"addNote\", \"CaseFieldID\": \"note\"}]");
        write(root, "AuthorisationCaseField/addNote.json",
            "[{\"CaseFieldID\": \"note\", \"AccessProfile\": \"caseworker\", \"CRUD\": \"CRU\"}]");
        write(root, "ComplexTypes/Address_1.json",
            "[{\"ID\": \"Address\", \"ListElementCode\": \"line1\"}]");
        write(root, "ComplexTypes/Address_2.json",
            "[{\"ID\": \"Address\", \"ListElementCode\": \"postcode\"}]");
        write(root, "FixedLists/Gender.json",
            "[{\"ID\": \"Gender\", \"ListElementCode\": \"male\"}]");
        write(root, "CaseTypeTab/CaseTypeTab_1.json",
            "[{\"TabID\": \"notes\", \"CaseFieldID\": \"note\"}]");
        write(root, "CaseEventToComplexTypes/addNote/note.json",
            "[{\"ID\": \"note\", \"CaseEventID\": \"addNote\", \"CaseFieldID\": \"note\","
                + " \"ListElementCode\": \"detail\"}]");
        write(root, "AuthorisationCaseEvent/AuthorisationCaseEvent.json",
            "[{\"CaseEventID\": \"addNote\", \"AccessProfile\": \"caseworker\", \"CRUD\": \"CRU\"}]");
        write(root, "SearchCasesResultFields/SearchCasesResultFields.json",
            "[{\"CaseFieldID\": \"note\", \"UseCase\": \"WORKALLOCATION\"}]");
        write(root, "CaseField.json", "[{\"ID\": \"note\", \"Label\": \"Note\", \"FieldType\": \"Text\"}]");

        Map<String, List<Map<String, Object>>> sheets = NormalisingCcdConfigComparator.aggregateDirectory(root);

        assertThat(sheets).containsOnlyKeys("CaseEvent", "CaseEventToFields", "AuthorisationCaseField",
            "ComplexTypes", "FixedLists", "CaseTypeTab", "EventToComplexTypes", "AuthorisationCaseEvent",
            "SearchCasesResultFields", "CaseField");
        assertThat(sheets.get("CaseEvent")).hasSize(2);
        assertThat(sheets.get("ComplexTypes")).hasSize(2);
        assertThat(sheets.get("EventToComplexTypes")).hasSize(1);
        assertThat(sheets.get("CaseField")).hasSize(1);
    }

    // ---- End to end ----

    @Test
    public void endToEndEquivalenceAcrossFragmentedOutputAndSuperficialDifferences() throws IOException {
        File root = tmp.getRoot();
        write(root, "CaseEvent/addNote.json",
            "[{\"ID\": \"addNote\", \"Name\": \"Add note\", \"LiveFrom\": \"01/01/2017\","
                + " \"SecurityClassification\": \"Public\", \"ShowSummary\": \"N\","
                + " \"CallBackURLAboutToSubmitEvent\": \"${CASE_API_URL}/callbacks/about-to-submit\"}]");
        write(root, "CaseField.json",
            "[{\"ID\": \"note\", \"Label\": \"Note\", \"FieldType\": \"Text\","
                + " \"SecurityClassification\": \"Public\", \"LiveFrom\": \"01/01/2017\"},"
                + " {\"ID\": \"caseHistory\", \"Label\": \"History\", \"FieldType\": \"CaseHistoryViewer\"}]");
        write(root, "AuthorisationCaseEvent/AuthorisationCaseEvent.json",
            "[{\"CaseEventID\": \"addNote\", \"AccessProfile\": \"caseworker\", \"CRUD\": \"CRU\","
                + " \"LiveFrom\": \"01/01/2017\"}]");
        write(root, "CaseEventToFields/addNote.json",
            "[{\"CaseEventID\": \"addNote\", \"CaseFieldID\": \"note\", \"PageID\": 1,"
                + " \"PageFieldDisplayOrder\": 1, \"PageDisplayOrder\": 1}]");

        Map<String, List<Map<String, Object>>> expected = new LinkedHashMap<>();
        // The callback URL is carried through verbatim now, so the expected side holds the same raw
        // value as the generated file (only LiveFrom/other cosmetic differences are reconciled).
        expected.put("CaseEvent", rows(
            row("ID", "addNote", "Name", "Add note", "LiveFrom", "01/06/2021",
                "CallBackURLAboutToSubmitEvent", "${CASE_API_URL}/callbacks/about-to-submit")));
        expected.put("CaseField", rows(
            row("ID", "note", "Name", "Note", "FieldType", "Text", "LiveFrom", "01/06/2021")));
        expected.put("AuthorisationCaseEvent", rows(
            row("CaseEventID", "addNote", "UserRole", "caseworker", "CRUD", "CRU")));
        expected.put("CaseEventToFields", rows(
            row("CaseEventID", "addNote", "CaseFieldID", "note", "PageID", "1")));

        ComparisonResult result = NormalisingCcdConfigComparator.compareWithDirectory(expected, root);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules()).isNotEmpty();
    }

    // ---- Failure reporting ----

    @Test
    public void unexplainedDifferencesFailWithReadableMessages() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "forename", "Label", "Forename", "FieldType", "Text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "forename", "Label", "Forename", "FieldType", "Number")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures()).hasSize(1);
        assertThat(result.getFailures().get(0))
            .contains("CaseField")
            .contains("[forename]")
            .contains("FieldType")
            .contains("Text")
            .contains("Number");

        assertThatThrownBy(() -> NormalisingCcdConfigComparator.assertEquivalent(expected, actual))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("CaseField")
            .hasMessageContaining("FieldType");
    }

    @Test
    public void unmatchedRowsAreReportedOnBothSides() {
        Map<String, List<Map<String, Object>>> expected = sheets("State",
            rows(row("ID", "Open", "Name", "Open"), row("ID", "Closed", "Name", "Closed")));
        Map<String, List<Map<String, Object>>> actual = sheets("State",
            rows(row("ID", "Open", "Name", "Open"), row("ID", "Archived", "Name", "Archived")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).isFalse();
        assertThat(result.getFailures())
            .anySatisfy(failure -> assertThat(failure).contains("Closed").contains("no match in actual"))
            .anySatisfy(failure -> assertThat(failure).contains("Archived").contains("unexpected row"));
    }

    @Test
    public void inputsAreNotMutatedByComparison() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "addNote", "LiveFrom", "01/01/2019")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "addNote", "LiveFrom", "01/01/2017")));

        NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(expected.get("CaseEvent").get(0)).containsEntry("LiveFrom", "01/01/2019");
        assertThat(actual.get("CaseEvent").get(0)).containsEntry("LiveFrom", "01/01/2017");
    }

    // ---- REDUNDANT_FIELD_TYPE_PARAMETER ----

    @Test
    public void redundantFieldTypeParameterOnBaseTypeIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "flag", "FieldType", "YesOrNo", "FieldTypeParameter", "flag")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "flag", "FieldType", "YesOrNo")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("REDUNDANT_FIELD_TYPE_PARAMETER"));
    }

    @Test
    public void fieldTypeParameterOnListTypeStillFails() {
        // FixedList consumes its parameter, so a parameter present on only one side is behavioural.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "colour", "FieldType", "FixedList", "FieldTypeParameter", "colours")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "colour", "FieldType", "FixedList")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void differingFieldTypeParameterPresentOnBothSidesStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "x", "FieldType", "Collection", "FieldTypeParameter", "Document")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "x", "FieldType", "Collection", "FieldTypeParameter", "Text")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- EMPTY_CRUD_AUTHORISATION ----

    @Test
    public void emptyCrudAuthorisationRowPresentOnOneSideIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "CRUD")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "CRUD"),
                row("CaseTypeID", "X", "AccessProfile", "idam-role", "CRUD", "")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("EMPTY_CRUD_AUTHORISATION"));
    }

    @Test
    public void nonEmptyCrudRowMissingOnOneSideStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "CRUD")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "CRUD"),
                row("CaseTypeID", "X", "AccessProfile", "idam-role", "CRUD", "R")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void emptyCrudRowPresentOnBothSidesIsCompared() {
        // When both sides carry the row, empty CRUD is not dropped; a CRUD mismatch still fails.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "R")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- CRUD_LETTER_ORDER ----

    @Test
    public void crudLetterOrderDifferenceIsForgiven() {
        // The importer parses CRUD as an order-independent set (AuthorisationParser#parseCrud uses
        // String.contains per letter), so CUR and CRU grant identically.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "f", "AccessProfile", "caseworker", "CRUD", "CUR")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "f", "AccessProfile", "caseworker", "CRUD", "CRU")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CRUD_LETTER_ORDER"));
    }

    @Test
    public void crudLetterOrderIsCaseInsensitive() {
        // The importer upper-cases before membership testing, so lower-case letters canonicalise too.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseEvent",
            rows(row("CaseEventID", "e", "AccessProfile", "caseworker", "CRUD", "ucr")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseEvent",
            rows(row("CaseEventID", "e", "AccessProfile", "caseworker", "CRUD", "CRU")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches())
            .as("case-insensitive CRUD canonicalisation").isTrue();
    }

    @Test
    public void crudLetterOrderKeepsAuthorisationComplexTypeRowsMatched() {
        // CRUD is part of the AuthorisationComplexType primary key, so canonicalising in
        // normaliseSheets (before matching) is what keeps an order-only difference from splitting
        // the row into a no-match/unexpected pair.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationComplexType",
            rows(row("CaseFieldID", "f", "ListElementCode", "m", "AccessProfile", "caseworker",
                "CRUD", "CUR")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationComplexType",
            rows(row("CaseFieldID", "f", "ListElementCode", "m", "AccessProfile", "caseworker",
                "CRUD", "CRU")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void genuineCrudSetDifferenceStillFails() {
        // Different letters (not an anagram) sort to different strings and must still fail.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "D")));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseType",
            rows(row("CaseTypeID", "X", "AccessProfile", "caseworker", "CRUD", "CRUD")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- CASE_TYPE_TAB role scoping ----

    @Test
    public void roleScopedTabWithSuffixedIdAndFirstRowOnlyRoleIsReconciled() {
        // Generator: TabID gets the role appended and UserRole appears on the first field only.
        // Input: plain TabID with AccessProfile repeated on every field row.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(row("TabID", "outcome", "CaseFieldID", "a", "AccessProfile", "caseworker-x"),
                row("TabID", "outcome", "CaseFieldID", "b", "AccessProfile", "caseworker-x")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(row("TabID", "outcomecaseworker-x", "CaseFieldID", "a",
                    "UserRole", "caseworker-x", "Channel", "CaseWorker"),
                row("TabID", "outcomecaseworker-x", "CaseFieldID", "b",
                    "UserRole", "", "Channel", "CaseWorker")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void tabWithGenuinelyDifferentRoleStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseTypeTab",
            rows(row("TabID", "outcome", "CaseFieldID", "a", "AccessProfile", "caseworker-x")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseTypeTab",
            rows(row("TabID", "outcomecaseworker-y", "CaseFieldID", "a",
                "UserRole", "caseworker-y", "Channel", "CaseWorker")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- empty/absent key-column canonicalisation ----

    @Test
    public void emptyListElementCodeKeyMatchesAbsentListElementCode() {
        Map<String, List<Map<String, Object>>> expected = sheets("SearchCasesResultFields",
            rows(row("CaseFieldID", "name", "UseCase", "orgcases", "ListElementCode", "",
                "Label", "Name")));
        Map<String, List<Map<String, Object>>> actual = sheets("SearchCasesResultFields",
            rows(row("CaseFieldID", "name", "UseCase", "orgcases", "Label", "Name")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    // ---- COLLECTION_ELEMENT_TYPE ----

    @Test
    public void collectionOfStringMatchesCollectionOfText() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "notes", "FieldType", "Collection", "FieldTypeParameter", "Text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "notes", "FieldType", "Collection", "FieldTypeParameter", "String")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("COLLECTION_ELEMENT_TYPE"));
    }

    @Test
    public void stringElementOnNonCollectionStillFails() {
        // The String->Text canonicalisation is scoped to Collection; a Complex reference is not.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseField",
            rows(row("ID", "x", "FieldType", "Complex", "FieldTypeParameter", "Text")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseField",
            rows(row("ID", "x", "FieldType", "Complex", "FieldTypeParameter", "String")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- SHOW_CONDITION_WHITESPACE ----

    @Test
    public void trailingWhitespaceOnShowConditionIsForgiven() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "f",
                "FieldShowCondition", "isAdmin=\"Yes\" ")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "f",
                "FieldShowCondition", "isAdmin=\"Yes\"")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("SHOW_CONDITION_WHITESPACE"));
    }

    @Test
    public void differentShowConditionExpressionStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "f",
                "FieldShowCondition", "isAdmin=\"Yes\"")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEventToFields",
            rows(row("CaseEventID", "e", "CaseFieldID", "f",
                "FieldShowCondition", "isAdmin=\"No\"")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    // ---- ACCESS_CONTROL_EXPANSION ----

    @Test
    public void userRolesArrayRowExpandsToPerRoleRows() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "f", "CRUD", "R",
                "UserRoles", List.of("[SOLICITORA]", "[SOLICITORB]"))));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "f", "AccessProfile", "[SOLICITORA]", "CRUD", "R"),
                row("CaseFieldID", "f", "AccessProfile", "[SOLICITORB]", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("ACCESS_CONTROL_EXPANSION"));
    }

    @Test
    public void accessControlArrayRowExpandsPerElementCrud() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseEvent",
            rows(row("CaseEventID", "e", "AccessControl", List.of(
                accessControl(List.of("caseworker-a"), "CR"),
                accessControl(List.of("caseworker-b", "caseworker-c"), "R")))));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseEvent",
            rows(row("CaseEventID", "e", "AccessProfile", "caseworker-a", "CRUD", "CR"),
                row("CaseEventID", "e", "AccessProfile", "caseworker-b", "CRUD", "R"),
                row("CaseEventID", "e", "AccessProfile", "caseworker-c", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
    }

    @Test
    public void expandedArrayRowWithWrongCrudStillFails() {
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "f", "CRUD", "CRUD",
                "UserRoles", List.of("[SOLICITORA]"))));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationCaseField",
            rows(row("CaseFieldID", "f", "AccessProfile", "[SOLICITORA]", "CRUD", "R")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void authorisationComplexTypeNestedArrayExpandsToFlatGrantComplexTypeRows() {
        // The converter now emits flat per-role grantComplexType rows for AuthorisationComplexType,
        // so the sheet is expanded like every other Authorisation* sheet: the input's nested
        // AccessControl[] shape (fpl's shape) flattens to the flat per-role rows the generator emits.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationComplexType",
            rows(row("CaseFieldID", "children1", "ListElementCode", "party",
                "AccessControl", List.of(accessControl(List.of("[BARRISTER]"), "R")))));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationComplexType",
            rows(row("CaseFieldID", "children1", "ListElementCode", "party",
                "AccessProfile", "[BARRISTER]", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("ACCESS_CONTROL_EXPANSION"));
    }

    @Test
    public void authorisationComplexTypeUserRolesArrayExpandsToFlatRows() {
        // fpl's flat UserRoles[] shape (a row granting several roles one CRUD) flattens to one flat
        // per-role row each, matching the generator's grantComplexType output.
        Map<String, List<Map<String, Object>>> expected = sheets("AuthorisationComplexType",
            rows(row("CaseFieldID", "allocatedJudge", "ListElementCode", "judgeTitle", "CRUD", "R",
                "UserRoles", List.of("[LASOLICITOR]", "[LASHARED]"))));
        Map<String, List<Map<String, Object>>> actual = sheets("AuthorisationComplexType",
            rows(row("CaseFieldID", "allocatedJudge", "ListElementCode", "judgeTitle",
                    "AccessProfile", "[LASOLICITOR]", "CRUD", "R"),
                row("CaseFieldID", "allocatedJudge", "ListElementCode", "judgeTitle",
                    "AccessProfile", "[LASHARED]", "CRUD", "R")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("ACCESS_CONTROL_EXPANSION"));
    }

    @Test
    public void trailingWhitespaceOnListElementCodeIsAbsorbed() {
        // ia declares a ComplexTypes member with a trailing space on its ListElementCode
        // ("imageRenderingLocation ") that references a FixedList without one; the importer trims
        // identifier cells, so the SDK-emitted trimmed code must match.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "DocumentImage", "ListElementCode", "imageRenderingLocation ",
                "FieldType", "FixedList", "ElementLabel", "Where?")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "DocumentImage", "ListElementCode", "imageRenderingLocation",
                "FieldType", "FixedList", "ElementLabel", "Where?")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("IDENTIFIER_WHITESPACE"));
    }

    @Test
    public void interiorDifferenceOnIdentifierStillFails() {
        // The rule trims only surrounding whitespace; a genuinely different id must still fail.
        Map<String, List<Map<String, Object>>> expected = sheets("ComplexTypes",
            rows(row("ID", "DocumentImage", "ListElementCode", "imageRenderingLocation",
                "ElementLabel", "Where?")));
        Map<String, List<Map<String, Object>>> actual = sheets("ComplexTypes",
            rows(row("ID", "DocumentImage", "ListElementCode", "imageRenderingPlace",
                "ElementLabel", "Where?")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void vestigialMidEventColumnOnCaseEventIsDropped() {
        // sscs carries a CallBackURLMidEvent on the CaseEvent sheet; mid-event is a per-page
        // (CaseEventToFields) property in CCD, so the SDK never emits it on CaseEvent and the
        // importer ignores it there. Both sides drop it, so the rows match.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "amendHearingOutcome", "Name", "Amend",
                "CallBackURLMidEvent", "http://legacy/mid",
                "RetriesTimeoutURLMidEvent", "5,5")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "amendHearingOutcome", "Name", "Amend")));

        ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);

        assertThat(result.matches()).as(result.report()).isTrue();
        assertThat(result.getAppliedRules())
            .anySatisfy(rule -> assertThat(rule).startsWith("CASE_EVENT_MID_EVENT"));
    }

    @Test
    public void nonMidEventCaseEventDifferenceStillFails() {
        // The rule drops only the two mid-event columns on CaseEvent; a real difference on any
        // other column must still fail.
        Map<String, List<Map<String, Object>>> expected = sheets("CaseEvent",
            rows(row("ID", "amendHearingOutcome", "Name", "Amend",
                "CallBackURLMidEvent", "http://legacy/mid")));
        Map<String, List<Map<String, Object>>> actual = sheets("CaseEvent",
            rows(row("ID", "amendHearingOutcome", "Name", "Amended")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches()).isFalse();
    }

    @Test
    public void jurisdictionMatchesByIdSoAColumnDiffSurfaces() {
        // With Jurisdiction keyed by ID, a Shuttered flag present on one side surfaces as a single
        // column diff rather than an unreconcilable whole-row no-match.
        Map<String, List<Map<String, Object>>> expected = sheets("Jurisdiction",
            rows(row("ID", "IA", "Name", "Immigration", "Shuttered", "Yes")));
        Map<String, List<Map<String, Object>>> actual = sheets("Jurisdiction",
            rows(row("ID", "IA", "Name", "Immigration", "Shuttered", "Yes")));

        assertThat(NormalisingCcdConfigComparator.compare(expected, actual).matches())
            .as("identical keyed Jurisdiction rows match").isTrue();
    }

    // ---- helpers ----

    private static Map<String, Object> accessControl(List<String> roles, String crud) {
        Map<String, Object> ac = new LinkedHashMap<>();
        ac.put("UserRoles", roles);
        ac.put("CRUD", crud);
        return ac;
    }

    private static Map<String, List<Map<String, Object>>> sheets(String name, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> sheets = new LinkedHashMap<>();
        sheets.put(name, rows);
        return sheets;
    }

    @SafeVarargs
    private static List<Map<String, Object>> rows(Map<String, Object>... rows) {
        return new ArrayList<>(List.of(rows));
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }

    private static void write(File root, String relativePath, String content) throws IOException {
        File file = new File(root, relativePath);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }
}
