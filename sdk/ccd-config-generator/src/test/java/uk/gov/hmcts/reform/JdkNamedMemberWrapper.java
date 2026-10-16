package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * A complex type carrying a member literally named {@code value}, the shape that used to abort the
 * whole generator run.
 *
 * <p>{@code CaseEventToComplexTypesGenerator.expand} tests each placed member with
 * {@code isUnwrappedField(field.getClazz(), <parent's root field name>)} — the member's <em>own</em>
 * type as the lookup class. Descending through this member therefore asks whether
 * {@code String.class} has a field named {@code value}, which it does: {@code String}'s private
 * {@code byte[] value}. The lookup used to call {@code ReflectionUtils.makeAccessible} on whatever it
 * found, so the hit threw {@code InaccessibleObjectException} ("module java.base does not
 * \"opens java.lang\"") and no definition was emitted at all.
 *
 * <p>Real definitions have this shape: finrem's {@code CaseEventToComplexTypes} addresses
 * {@code ordersToSend} with {@code ListElementCode}s like {@code value.documentName} — a collection
 * wrapper spelled out in the path — across seven rows of {@code FR_sendOrder}.
 */
@Data
@ComplexType(name = "JdkNamedMemberWrapper", generate = true)
public class JdkNamedMemberWrapper {

  @CCD(label = "Order")
  private JdkNamedMemberOrder value;
}
