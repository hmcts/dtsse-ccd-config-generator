package uk.gov.hmcts.example.model.common;

import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A class that ALREADY carries a {@code @ComplexType} — either the team wrote it, or this patch has
 * been applied once before. The ID pin must refuse it outright: a second annotation would not compile,
 * and overwriting the team's own {@code name}/{@code label}/{@code border} choices is not the patch's
 * call. This is also what makes the op idempotent, which every patch op is required to be.
 *
 * <p>The class name matches definition ID {@code pinnedByTeamCT} case-insensitively (how
 * {@code ModelSourceIndex.complexTypeClass} binds a camelCase ID to a PascalCase class), so the type
 * really does reach the pin — while the existing {@code name} deliberately DIFFERS from that ID, making
 * the refusal observable: were the emitter to pin regardless, a second
 * {@code @ComplexType(name = "pinnedByTeamCT", …)} would appear.
 */
@ComplexType(name = "teamsOwnChoice", generate = true)
public class PinnedByTeamCT {

  private String note;
}
