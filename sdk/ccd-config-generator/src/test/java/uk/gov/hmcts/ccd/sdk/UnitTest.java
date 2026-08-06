package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.example.cyclic.Child;
import uk.gov.hmcts.example.cyclic.CyclicCaseData;
import uk.gov.hmcts.example.cyclic.Parent;
import uk.gov.hmcts.example.missingcomplex.Applicant;
import uk.gov.hmcts.example.missingcomplex.MissingComplex;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static org.assertj.core.api.Assertions.assertThat;

public class UnitTest {

  @Test
  public void npeBug() {
    class NPEBug implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.event("addNotes")
          .forStates(State.Submitted, State.Open, State.Deleted)
          .fields()
          .readonly(CaseData::getProceeding)
          .complex(CaseData::getJudgeAndLegalAdvisor);
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator = new ConfigResolver<>(List.of(new NPEBug()));
    generator.resolveCCDConfig();
  }

  @Test
  public void missingComplexBug() {
    class MissingBug implements CCDConfig<MissingComplex, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<MissingComplex, State, UserRole> builder) {
      }
    }

    ConfigResolver<MissingComplex, State, UserRole> generator = new ConfigResolver<>(List.of(new MissingBug()));
    ResolvedCCDConfig<MissingComplex, State, UserRole> resolved = generator.resolveCCDConfig();
    assertThat(resolved.types).containsKeys(Applicant.class);
  }

  /**
   * The complex-type walk covers every field type, not just those under the base package, so it
   * reaches JDK and third-party internals. A single java.util.Date member is enough to enter the
   * JDK's own Date -> BaseCalendar$Date -> Era -> CalendarDate -> Era cycle, which used to recurse
   * until the stack was exhausted (prl's Document.documentCreatedOn hit exactly this).
   */
  @Test
  public void cyclicComplexTypeGraphTerminates() {
    class CyclicConfig implements CCDConfig<CyclicCaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CyclicCaseData, State, UserRole> builder) {
      }
    }

    ConfigResolver<CyclicCaseData, State, UserRole> generator =
        new ConfigResolver<>(List.of(new CyclicConfig()));
    ResolvedCCDConfig<CyclicCaseData, State, UserRole> resolved = generator.resolveCCDConfig();

    // Both halves of the service's own Parent <-> Child cycle are still resolved — cutting the
    // regress must not prune reachable types. Each records the DEEPEST level it was reached at (the
    // pre-existing rule that orders ComplexTypes declarations): Parent is first seen at 0 from the
    // root, then again at 2 via Child, so it settles at 2.
    assertThat(resolved.types).containsEntry(Parent.class, 2).containsEntry(Child.class, 1);
  }
}
