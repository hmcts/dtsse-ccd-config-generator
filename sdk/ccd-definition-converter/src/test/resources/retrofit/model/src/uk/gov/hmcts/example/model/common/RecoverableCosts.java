package uk.gov.hmcts.example.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A complex type using Lombok {@code @AllArgsConstructor} whose all-args constructor a subclass
 * ({@link RecoverableCostsSection}) calls positionally via {@code super(...)}, as Civil's
 * {@code FixedRecoverableCosts}/{@code FixedRecoverableCostsSection} do. Appending a synthesised
 * field widens the generated all-args constructor from 2 to 3 args, which on its own would leave the
 * subclass's two-arg {@code super(...)} call with no matching constructor (bug B4).
 *
 * <p>The patch REPAIRS that here rather than refusing the member: it adds an explicit narrow
 * constructor over this class's pre-synthesis field list delegating {@code this(band, reasons, null)},
 * which the unchanged subclass binds to. Safe because {@code @AllArgsConstructor} is EXPLICIT — see
 * {@link BuilderOnlyCosts} for the inferred-constructor shape that must still be refused.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverableCosts {

  private String band;

  private String reasons;
}
