package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.ccd.sdk.diff.CcdConfigComparator;

@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class E2EConfigGenerationTests {

    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;

    @Before
    public void before() {
        generator.generateAllCaseTypesToJSON(tmp.getRoot());
        // Generate a second time to ensure existing config is correctly merged.
        generator.generateAllCaseTypesToJSON(tmp.getRoot());
    }

    @Test
    public void generatesCareSupervisionEPO() {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(tmp.getRoot(), "CARE_SUPERVISION_EPO"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("CARE_SUPERVISION_EPO");
        CcdConfigComparator.assertEquivalent(expected, actual);
    }

    @SneakyThrows
    @Test
    public void respectsComplexTypeOrdering() {
        File expected = resourceFile("CARE_SUPERVISION_EPO/ComplexTypes/HearingBooking.json");
        File actual = new File(tmp.getRoot(), "CARE_SUPERVISION_EPO/ComplexTypes/HearingBooking.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.STRICT);
    }

    @Test
    public void testCustomHistoryTabOrder() {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(tmp.getRoot(), "CustomHistory/CaseTypeTab"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap("CustomHistory/CaseTypeTab");
        CcdConfigComparator.assertEquivalent(expected, actual);
    }

    @SneakyThrows
    @Test
    public void shuttersAllRolesExceptExcludedRole() {
        File expected = resourceFile("Shuttered/AuthorisationCaseType.json");
        File actual = new File(tmp.getRoot(), "Shuttered/AuthorisationCaseType.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsOnlyExplicitStateGrants() {
        File expected = resourceFile("ExplicitStateGrants/AuthorisationCaseState.json");
        File actual = new File(tmp.getRoot(), "ExplicitStateGrants/AuthorisationCaseState.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsPerEventComplexTypeMemberOverrides() {
        // A complex field whose members carry fluent .eventLabel/.eventHint plus a show condition,
        // and a nested member override, must produce the EventToComplexTypes rows verbatim.
        Map<String, File> actual = CcdConfigComparator.dirToMap(
            new File(tmp.getRoot(), "EventComplexMember/CaseEventToComplexTypes"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap(
            "EventComplexMember/CaseEventToComplexTypes");
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsPerEventComplexCollectionElementOverrides() {
        // The element members of a Collection field, overridden per event through the element-typed
        // .complex(getter, Class) scope (plus a nested collection scope), must produce the
        // CaseEventToComplexTypes rows in input shape (no index) with the HintText tri-state applied.
        Map<String, File> actual = CcdConfigComparator.dirToMap(
            new File(tmp.getRoot(), "EventComplexCollection/CaseEventToComplexTypes"));
        Map<String, File> expected = CcdConfigComparator.resourcesDirToMap(
            "EventComplexCollection/CaseEventToComplexTypes");
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void openingAnElementScopeLeavesTheCollectionFieldRowUntouched() {
        // Opening a member scope on the collection via .complex(getter, Class) must register no root
        // field, so the collection field's own CaseEventToFields row is byte-identical to what a bare
        // .optional(getter) placement produces (no extra row, no showSummary/mutableList side effects).
        File expected = resourceFile("EventComplexCollection/CaseEventToFields/create.json");
        File actual = new File(tmp.getRoot(), "EventComplexCollection/CaseEventToFields/create.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsConfiguredBanner() {
        File expected = resourceFile("BannerFeature/Banner.json");
        File actual = new File(tmp.getRoot(), "BannerFeature/Banner.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    public void omitsBannerWhenNotConfigured() {
        assertFalse(new File(tmp.getRoot(), "CARE_SUPERVISION_EPO/Banner.json").exists());
    }

    @SneakyThrows
    @Test
    public void preservesRenamedFixedListId() {
        // A generated fixed-list enum with a PascalCase Java class name (RenamedFixedListChoice) but
        // a distinct @ComplexType(name) list ID must emit that list ID — not the class name — as the
        // FixedLists sheet ID and file name. This is the SDK half of the converter's FL_ rename
        // (finding #4): the class is renamed for readability while the wire ID round-trips exactly.
        File expected = resourceFile("RenamedFixedList/FixedLists/FL_renamedChoice.json");
        File actual = new File(tmp.getRoot(), "RenamedFixedList/FixedLists/FL_renamedChoice.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
        // The class-named file must NOT be produced.
        assertFalse(new File(tmp.getRoot(),
            "RenamedFixedList/FixedLists/RenamedFixedListChoice.json").exists());

        // ...and the referencing CaseField's FieldTypeParameter must be the list ID too.
        String caseField = Resources.toString(
            new File(tmp.getRoot(), "RenamedFixedList/CaseField.json").toURI().toURL(),
            StandardCharsets.UTF_8);
        assertThat(caseField)
            .contains("\"FieldTypeParameter\"")
            .contains("FL_renamedChoice")
            .doesNotContain("RenamedFixedListChoice");
    }

    @SneakyThrows
    @Test
    public void mapsUnregisteredRolesToAccessProfiles() {
        // The two org/IDAM roles appear in RoleToAccessProfiles verbatim...
        File expectedProfiles = resourceFile("RoleMappings/RoleToAccessProfiles.json");
        File actualProfiles = new File(tmp.getRoot(), "RoleMappings/RoleToAccessProfiles.json");
        CcdConfigComparator.assertEquals(expectedProfiles, actualProfiles, JSONCompareMode.NON_EXTENSIBLE);

        // ...but must NOT be registered as UserRoles in AuthorisationCaseType, which only lists
        // enum-backed roles. A plain-string mapping never registers a role.
        String auth = Resources.toString(
            new File(tmp.getRoot(), "RoleMappings/AuthorisationCaseType.json").toURI().toURL(),
            StandardCharsets.UTF_8);
        assertThat(auth)
            .doesNotContain("caseworker-rolemap-system")
            .doesNotContain("caseworker-rolemap-caseofficer");
    }

    @SneakyThrows
    @Test
    public void stampsCaseRoleJurisdictionWhenOptedIn() {
        File expected = resourceFile("RoleMappings/CaseRoles.json");
        File actual = new File(tmp.getRoot(), "RoleMappings/CaseRoles.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void honoursJsonPropertyOnStateIds() {
        // CASE_MANAGEMENT carries @JsonProperty("PREPARE_FOR_HEARING"); Open has no @JsonProperty.
        // Every sheet that serialises a state must use the resolved id on the annotated constant and
        // the constant name on the plain one.
        File expectedState = resourceFile("JsonPropertyState/State.json");
        File actualState = new File(tmp.getRoot(), "JsonPropertyState/State.json");
        CcdConfigComparator.assertEquals(expectedState, actualState, JSONCompareMode.NON_EXTENSIBLE);

        File expectedEvent = resourceFile("JsonPropertyState/CaseEvent/create.json");
        File actualEvent = new File(tmp.getRoot(), "JsonPropertyState/CaseEvent/create.json");
        CcdConfigComparator.assertEquals(expectedEvent, actualEvent, JSONCompareMode.NON_EXTENSIBLE);

        File expectedAuth = resourceFile("JsonPropertyState/AuthorisationCaseState.json");
        File actualAuth = new File(tmp.getRoot(), "JsonPropertyState/AuthorisationCaseState.json");
        CcdConfigComparator.assertEquals(expectedAuth, actualAuth, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void honoursExplicitStateDescription() {
        // CaseManagement carries @CCD(description = ...); Open has none and must default to Name.
        File expected = resourceFile("StateDescription/State.json");
        File actual = new File(tmp.getRoot(), "StateDescription/State.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsEventColumnsFlags() {
        // See uk.gov.hmcts.reform.EventColumnsCaseType: significant(), enableForDeletion() and
        // jurisdictionShuttered() each pin a column-graft replacement that is default-off.
        File expectedCaseType = resourceFile("EventColumns/CaseType.json");
        File actualCaseType = new File(tmp.getRoot(), "EventColumns/CaseType.json");
        CcdConfigComparator.assertEquals(expectedCaseType, actualCaseType, JSONCompareMode.NON_EXTENSIBLE);

        File expectedJurisdiction = resourceFile("EventColumns/Jurisdiction.json");
        File actualJurisdiction = new File(tmp.getRoot(), "EventColumns/Jurisdiction.json");
        CcdConfigComparator.assertEquals(expectedJurisdiction, actualJurisdiction, JSONCompareMode.NON_EXTENSIBLE);

        File expectedEvent = resourceFile("EventColumns/CaseEvent/close.json");
        File actualEvent = new File(tmp.getRoot(), "EventColumns/CaseEvent/close.json");
        CcdConfigComparator.assertEquals(expectedEvent, actualEvent, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsSmallColumns2Flags() {
        // See uk.gov.hmcts.reform.SmallColumns2CaseType: printableDocumentsUrl(), canSaveDraft(),
        // showSummaryContentOption() and nullifyByDefault() each pin a column-graft replacement
        // that is default-off.
        File expectedCaseType = resourceFile("SmallColumns2/CaseType.json");
        File actualCaseType = new File(tmp.getRoot(), "SmallColumns2/CaseType.json");
        CcdConfigComparator.assertEquals(expectedCaseType, actualCaseType, JSONCompareMode.NON_EXTENSIBLE);

        File expectedEvent = resourceFile("SmallColumns2/CaseEvent/create.json");
        File actualEvent = new File(tmp.getRoot(), "SmallColumns2/CaseEvent/create.json");
        CcdConfigComparator.assertEquals(expectedEvent, actualEvent, JSONCompareMode.NON_EXTENSIBLE);

        File expectedCaseEventToFields = resourceFile("SmallColumns2/CaseEventToFields/create.json");
        File actualCaseEventToFields = new File(tmp.getRoot(), "SmallColumns2/CaseEventToFields/create.json");
        CcdConfigComparator.assertEquals(expectedCaseEventToFields, actualCaseEventToFields,
            JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsFieldExtrasMetadata() {
        // See uk.gov.hmcts.reform.FieldExtrasCaseType: raw-string defaultValue(String), fluent
        // retainHiddenValue(), and the FieldCollectionBuilder lastField()-style fluent metadata
        // setters (caseEventFieldLabel/Hint, fieldShowCondition, displayContextParameter) usable
        // after a readonly/*NoSummary call each pin a column-graft replacement that is default-off.
        File expectedCaseEventToFields = resourceFile("FieldExtras/CaseEventToFields/create.json");
        File actualCaseEventToFields = new File(tmp.getRoot(), "FieldExtras/CaseEventToFields/create.json");
        CcdConfigComparator.assertEquals(expectedCaseEventToFields, actualCaseEventToFields,
            JSONCompareMode.NON_EXTENSIBLE);
    }

    @Test
    public void generatesDerivedConfig() {
        Map<String, File> actual = CcdConfigComparator.dirToMap(new File(tmp.getRoot(), "derived"));
        Map<String, File> expected = ImmutableMap.<String, File>builder()
            .putAll(CcdConfigComparator.resourcesDirToMap("CARE_SUPERVISION_EPO"))
            .putAll(CcdConfigComparator.resourcesDirToMap("derived"))
            .buildKeepingLast();
        CcdConfigComparator.assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE, "CaseTypeID");
    }

    @SneakyThrows
    @Test
    public void emitsSearchExtras() {
        // Pins the search/workbasket sub-builder: ListElementCode (several leaves per complex field),
        // FieldShowCondition on input sheets and ResultsOrdering on result sheets, plus role scoping.
        // One file per sheet keeps the snapshot on this feature's output and off unrelated generators.
        for (String sheet : new String[] {
            "SearchInputFields", "WorkBasketInputFields", "SearchResultFields", "WorkBasketResultFields"}) {
            File expected = resourceFile("SearchExtras/" + sheet + ".json");
            File actual = new File(tmp.getRoot(), "SearchExtras/" + sheet + ".json");
            CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
        }
    }

    @SneakyThrows
    @Test
    public void keepsSameNamedSearchPartiesDistinct() {
        // Two parties share a SearchPartyName but differ in SearchPartyCollectionFieldName; both rows
        // must survive (keying on name alone collapsed them last-wins).
        File expected = resourceFile("SearchPartyDuplicate/SearchParty.json");
        File actual = new File(tmp.getRoot(), "SearchPartyDuplicate/SearchParty.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsSearchCasesRoleAndUseCase() {
        // Role/use-case scoping of SearchCasesResultFields rows, keeping the historic default row
        // (empty UserRole, UseCase=orgcases) byte-identical alongside the opted-in rows.
        File expected = resourceFile("SearchCasesRole/SearchCasesResultFields/SearchCasesResultFields.json");
        File actual =
            new File(tmp.getRoot(), "SearchCasesRole/SearchCasesResultFields/SearchCasesResultFields.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    @Test
    public void emitsFieldTypeCompletionFields() {
        // See uk.gov.hmcts.reform.FieldTypeCompletionCaseData: typeOverride = WaysToPay pins the
        // new base FieldType constant, and the JudicialUser field pins the new predefined complex
        // type resolving to FieldType "JudicialUser" via its class-level @ComplexType.
        File expected = resourceFile("FieldTypeCompletion/CaseField.json");
        File actual = new File(tmp.getRoot(), "FieldTypeCompletion/CaseField.json");
        CcdConfigComparator.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @SneakyThrows
    private File resourceFile(String resourcePath) {
        URL url = Resources.getResource(resourcePath);
        return new File(url.toURI());
    }
}
