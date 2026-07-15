package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * A complex type from the ComplexTypes sheet, destined for a generated class annotated
 * {@code @ComplexType}. Types matching an SDK-predefined class in
 * {@code uk.gov.hmcts.ccd.sdk.type} (Document, AddressUK, OrganisationPolicy, …) are not
 * generated; the linker maps fields to the predefined class instead.
 */
@Value
@Builder
public class ComplexTypeModel {

  /** The ComplexTypes sheet ID — also the generated class's simple name. */
  String id;

  List<FieldModel> members;

  /**
   * Nesting depth in the complex-type reference graph; the SDK writes files as
   * {@code ComplexTypes/<depth>_<Type>.json} so import order satisfies references.
   */
  int depth;
}
