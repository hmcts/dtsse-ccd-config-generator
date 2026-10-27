package uk.gov.hmcts.example.model.common;

import lombok.Builder;
import lombok.Data;

/**
 * The subclass-{@code super(...)} shape that must STAY refused: its all-args constructor is INFERRED
 * from {@code @Builder} rather than declared with {@code @AllArgsConstructor}, and Lombok infers one
 * only while the class declares no constructor at all.
 *
 * <p>So the repair that fixes {@link RecoverableCosts} — adding an explicit narrow constructor for the
 * subclass's {@code super(...)} to bind to — is unsafe here: adding any constructor suppresses the
 * inference, and the generated builder then fails to compile ({@code constructor BuilderOnlyCosts …
 * required: String,String found: String,String,String}, verified against Lombok 1.18.38). The
 * definition-only member must route to a MANUAL_PLACEMENT gap instead.
 */
@Data
@Builder(toBuilder = true)
public class BuilderOnlyCosts {

  private String cap;

  private String note;
}
