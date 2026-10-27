package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link IgnoredReachCaseType}, covering all three reachability polarities at once:
 * {@link #ignoredField} and {@link #jsonIgnoredField} reach {@link IgnoredReachNested} and nothing
 * else does, so that type must vanish entirely; {@link #ignoredSharedField} and {@link #sharedField}
 * both reach {@link IgnoredReachShared}, which must survive because a live field still references
 * it.
 *
 * <p>Models the retrofit shape this exists for: a team's model class carries fields the hand-written
 * definition never had (HMC integration types, report types, bundling types), which the retrofit
 * patch marks {@code @CCD(ignore = true)}. Before ignored fields were filtered out of reachability
 * those fields' types still emitted ComplexTypes rows the definition does not contain.
 */
@Data
public class IgnoredReachCaseData {

  /**
   * A static a real service's model carries beside its data. Nothing marks it ignored — on a
   * hand-written model the question never comes up — but it is not case data and must emit no row.
   * sscs's Lombok {@code @Slf4j} loggers and {@code CorrespondenceDetails}' private
   * {@code DateTimeFormatter} emitted {@code ComplexTypes} rows of {@code FieldType=Logger} and
   * {@code FieldType=DateTimeFormatter}, types no CCD definition can name. Held here, and on
   * {@link IgnoredReachShared}, so both the case-data and the complex-type path are covered; the
   * snapshot's {@code NON_EXTENSIBLE} compare is what fails if either emits.
   */
  private static final String ONLY_A_CONSTANT = "not case data";

  @CCD(label = "A base field", access = {SolicitorAccess.class})
  private String baseField;

  @CCD(label = "A live complex field", access = {SolicitorAccess.class})
  private IgnoredReachShared sharedField;

  @CCD(ignore = true)
  private IgnoredReachNested ignoredField;

  @JsonIgnore
  private IgnoredReachNested jsonIgnoredField;

  @CCD(ignore = true)
  private IgnoredReachShared ignoredSharedField;
}
