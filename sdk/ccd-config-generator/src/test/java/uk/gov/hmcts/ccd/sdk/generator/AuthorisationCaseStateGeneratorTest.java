package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

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
import uk.gov.hmcts.reform.IgnoredStateState;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

public class AuthorisationCaseStateGeneratorTest {

    private static final String CASE_TYPE = "TEST_CASE_TYPE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * A grant naming a constant of a DIFFERENT state enum to the one the case type declares must
     * still emit its row rather than failing the build.
     *
     * <p>{@code forStates(...)} takes the state type as a generic parameter, so erasure lets a shared
     * {@code EnumSet} constant declared on one state enum be handed to an event on a case type
     * parameterised by another. nfdiv does this in production: its {@code NFD_ExceptionRecord} case
     * type is {@code CCDConfig<ExceptionRecord, ExceptionRecordState, UserRole>}, yet
     * {@code CompleteAwaitingPaymentDcnProcessing} configures an event on it with
     * {@code forStates(State.POST_SUBMISSION_STATES)}. Testing the ignore flag against the case
     * type's declared state class threw {@code NoSuchFieldException: Holding} and failed
     * {@code nfdiv:generateCCDConfig} outright.
     */
    @Test
    public void grantOnAForeignStateEnumConstantDoesNotFailGeneration() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        // CaseManagement is declared by IgnoredStateState and by NO constant of State, the case
        // type's declared state enum — the shape nfdiv's Holding has. Granted through the raw type
        // because that is precisely what erasure permits at the forStates(...) call site in
        // production; there is no checked cast that reaches this shape.
        grantRaw(builder, IgnoredStateState.CaseManagement);

        List<Map<String, Object>> rows = generate(builder);

        assertThat(rows)
            .anySatisfy(row -> assertThat(row).containsEntry("CaseStateID", "CaseManagement"));
    }

    /**
     * The ignore flag is read off the constant's own declaring class, so a foreign constant carrying
     * {@code @CCD(ignore = true)} is still suppressed. This is the counterpart to
     * {@link #grantOnAForeignStateEnumConstantDoesNotFailGeneration}: recovering from the missing
     * field must not degrade into treating every foreign constant as emittable, or the intent of
     * honouring {@code ignore} on state constants would be lost for exactly the shapes that reach it.
     */
    @Test
    public void anIgnoredForeignStateConstantIsStillSuppressed() {
        ConfigBuilderImpl<CaseData, State, UserRole> builder = newBuilder();
        grantRaw(builder, IgnoredStateState.Unknown);

        List<Map<String, Object>> rows = generate(builder);

        assertThat(rows)
            .noneSatisfy(row -> assertThat(row).containsEntry("CaseStateID", "Unknown"));
    }

    /**
     * Grants on a state constant of an enum other than the builder's declared state type. Goes
     * through the raw type deliberately: a checked cast to {@code State} would throw
     * {@code ClassCastException} in the test itself, whereas the production shape never casts at all
     * — {@code forStates(S...)} erases to {@code Object[]} and accepts the foreign constant silently.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void grantRaw(ConfigBuilderImpl builder, Enum<?> foreignState) {
        builder.grant(foreignState, CRU, new UserRole[] {UserRole.HMCTS_ADMIN});
    }

    private ConfigBuilderImpl<CaseData, State, UserRole> newBuilder() {
        ResolvedCCDConfig<CaseData, State, UserRole> config = new ResolvedCCDConfig<>(
            CaseData.class, State.class, UserRole.class, Map.of(),
            ImmutableSet.copyOf(State.values()));
        ConfigBuilderImpl<CaseData, State, UserRole> builder = new ConfigBuilderImpl<>(config);
        builder.caseType(CASE_TYPE, "Test", "Test case type");
        return builder;
    }

    @SneakyThrows
    private List<Map<String, Object>> generate(ConfigBuilderImpl<CaseData, State, UserRole> builder) {
        ResolvedCCDConfig<CaseData, State, UserRole> config = builder.build();
        new AuthorisationCaseStateGenerator<CaseData, State, UserRole>().write(tmp.getRoot(), config);

        File output = new File(tmp.getRoot(), "AuthorisationCaseState.json");
        return MAPPER.readValue(output, new TypeReference<List<Map<String, Object>>>() {});
    }
}
