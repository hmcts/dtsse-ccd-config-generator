package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.reform.EventComplexMemberCaseData;
import uk.gov.hmcts.reform.EventComplexMemberState;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

public class BannerGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Two case types writing the same banner into one jurisdiction's {@code Banner.json} agree, so
     * the sheet keeps its single row.
     */
    @Test
    public void keepsOneRowWhenTwoCaseTypesWriteTheSameBanner() {
        write("CASE_A", "Service notice");
        write("CASE_B", "Service notice");

        assertThat(rows()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("BannerDescription", "Service notice"));
    }

    /**
     * The sheet has no key, so a second, different banner would otherwise merge onto the first and
     * be dropped without a word. The importer allows one banner per jurisdiction, so it fails instead.
     */
    @Test
    public void rejectsASecondCaseTypeWritingADifferentBanner() {
        write("CASE_A", "Service notice");

        assertThatThrownBy(() -> write("CASE_B", "Another notice"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CASE_B")
            .hasMessageContaining("BannerDescription");
    }

    private void write(String caseType, String description) {
        ResolvedCCDConfig<EventComplexMemberCaseData, EventComplexMemberState, UserRole> config =
            new ResolvedCCDConfig<>(
                EventComplexMemberCaseData.class, EventComplexMemberState.class, UserRole.class,
                Map.of(), ImmutableSet.copyOf(EventComplexMemberState.values()));
        ConfigBuilderImpl<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder =
            new ConfigBuilderImpl<>(config);
        builder.caseType(caseType, "Test", "Test case type");
        builder.banner(true, description, "https://example.com", "Read more");
        new BannerGenerator<EventComplexMemberCaseData, EventComplexMemberState, UserRole>()
            .write(tmp.getRoot(), builder.build());
    }

    @SneakyThrows
    private List<Map<String, Object>> rows() {
        return MAPPER.readValue(new File(tmp.getRoot(), "Banner.json"),
            new TypeReference<List<Map<String, Object>>>() {});
    }
}
