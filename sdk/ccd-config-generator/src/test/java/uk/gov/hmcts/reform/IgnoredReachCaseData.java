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
