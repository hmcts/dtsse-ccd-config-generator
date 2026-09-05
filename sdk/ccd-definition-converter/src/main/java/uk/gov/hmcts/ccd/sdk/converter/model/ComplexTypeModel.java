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

  /**
   * The ComplexTypes sheet ID — the CCD-facing type ID, preserved verbatim as the wire ID via
   * {@code @ComplexType(name = id)} when it differs from {@link #javaClassName}.
   */
  String id;

  /**
   * The generated class's Java-conventional (PascalCase) simple name. Decoupled from {@link #id} so
   * a camelCase definition ID ({@code benefit}) yields a conventional class ({@code Benefit}) while
   * the wire ID round-trips via {@code @ComplexType(name = id)}. Equals {@link #id} when the ID is
   * already a well-formed class name.
   */
  String javaClassName;

  List<FieldModel> members;

  /**
   * Nesting depth in the complex-type reference graph; the SDK writes files as
   * {@code ComplexTypes/<depth>_<Type>.json} so import order satisfies references.
   */
  int depth;
}
