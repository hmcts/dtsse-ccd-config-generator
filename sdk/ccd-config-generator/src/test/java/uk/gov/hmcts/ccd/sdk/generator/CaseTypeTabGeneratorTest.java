package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class CaseTypeTabGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void injectsACaseHistoryTabByDefault() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.tab("summary", "Summary").field("caseLocalAuthority");

        assertThat(tabIds(builder)).containsExactly("CaseHistory", "summary");
    }

    @Test
    public void noCaseHistoryTabSuppressesTheInjectedTab() {
        // A case type that shows case history from a tab of its own naming — sscs places caseHistory
        // on a per-role eventHistory_<role> tab — is not detected by the ID-keyed injection check, so
        // it would otherwise render two History tabs. This is the opt-out.
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.noCaseHistoryTab();
        builder.tab("eventHistory_judge", "History").field("caseHistory");

        List<Map<String, Object>> rows = generate(builder);
        assertThat(tabIds(rows)).containsExactly("eventHistory_judge");
        // The field itself is untouched — only the tab placing it is suppressed, so the definition's
        // own tab still shows case history.
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("TabID")).isEqualTo("eventHistory_judge");
            assertThat(row.get("CaseFieldID")).isEqualTo("caseHistory");
        });
    }

    @Test
    public void suppressingTheInjectedTabMakesTheDeclaredTabFirst() {
        // The injected tab occupies TabDisplayOrder 1 and pushes declared tabs to 2+. With it gone
        // the declared tabs must start at 1, or every tab's order is off by one against the input.
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.noCaseHistoryTab();
        builder.tab("summary", "Summary").field("caseLocalAuthority");

        assertThat(generate(builder))
            .allSatisfy(row -> assertThat(row.get("TabDisplayOrder")).isEqualTo(1));
    }

    @Test
    public void aDeclaredCaseHistoryTabIsEmittedWhetherOrNotTheSwitchIsSet() {
        // The switch suppresses only the injection. A case type that declares TabID=CaseHistory means
        // it, so calling noCaseHistoryTab() must not delete the tab it asked for.
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        builder.noCaseHistoryTab();
        builder.tab("CaseHistory", "History").field("caseHistory");

        assertThat(tabIds(builder)).containsExactly("CaseHistory");
    }

    private ConfigBuilderImpl<CaseData, State, UserRole> newBuilder() {
        ResolvedCCDConfig<CaseData, State, UserRole> config = new ResolvedCCDConfig<>(
            CaseData.class, State.class, UserRole.class, Map.of(),
            ImmutableSet.copyOf(State.values()));
        ConfigBuilderImpl<CaseData, State, UserRole> builder = new ConfigBuilderImpl<>(config);
        builder.caseType(CASE_TYPE, "Test", "Test case type");
        return builder;
    }

    private List<String> tabIds(ConfigBuilderImpl<CaseData, State, UserRole> builder) {
        return tabIds(generate(builder));
    }

    private List<String> tabIds(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (String) row.get("TabID")).distinct().toList();
    }

    /** Every generated CaseTypeTab row, read back from the per-tab files the generator writes. */
    @SneakyThrows
    private List<Map<String, Object>> generate(ConfigBuilderImpl<CaseData, State, UserRole> builder) {
        ResolvedCCDConfig<CaseData, State, UserRole> config = builder.build();
        new CaseTypeTabGenerator<CaseData, State, UserRole>().write(tmp.getRoot(), config);

        File[] files = new File(tmp.getRoot(), "CaseTypeTab").listFiles();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (files == null) {
            return rows;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            rows.addAll(MAPPER.readValue(file, new TypeReference<List<Map<String, Object>>>() {}));
        }
        return rows;
    }
}
