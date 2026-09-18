package uk.gov.hmcts.reform;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Pins the {@code @ComplexType(name)} FixedLists ID carrier: a generated fixed-list enum with a
 * PascalCase Java class name ({@link RenamedFixedListChoice}) but a distinct CCD list ID
 * ({@code FL_renamedChoice}) must emit that list ID as both the FixedLists sheet ID / file name and
 * the referencing field's {@code FieldTypeParameter}. This is the SDK half of finding #4's converter
 * rename; see {@code E2EConfigGenerationTests#preservesRenamedFixedListId}.
 */
@Component
public class RenamedFixedListCaseType
    implements CCDConfig<RenamedFixedListCaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<RenamedFixedListCaseData, State, UserRole> builder) {
    builder.caseType("RenamedFixedList", "RenamedFixedList", "Renamed fixed list case type");
  }
}
