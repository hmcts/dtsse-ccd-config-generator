package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * A generated HasAccessControl implementation: a named, reusable bundle of role-to-CRUD
 * grants referenced from {@code @CCD(access = ...)} on case data fields.
 *
 * <p>The linker derives these by de-duplicating the per-field role/CRUD maps in
 * AuthorisationCaseField (after subtracting the grants the SDK injects automatically),
 * so many fields share a small number of access classes.
 */
@Value
@Builder
public class AccessClassModel {

  /** The generated class's simple name, e.g. "CaseworkerAccess". */
  String className;

  /** Role id to CRUD permission string (subset of "CRUD"). */
  Map<String, String> grants;
}
