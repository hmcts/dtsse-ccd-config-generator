package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

/**
 * Jackson annotation inspector that filters out properties marked with
 * {@link External} during serialisation.
 *
 * <p>Used by a dedicated {@code ObjectMapper} instance in the decentralised runtime
 * (see usages in the repository) to ensure fields intended for external-only
 * consumption are stripped from the case_data.data blob.
 */
class FilterExternalFieldsInspector extends JacksonAnnotationIntrospector {

  /**
   * Signals to Jackson that a property should be ignored when it is annotated
   * with {@link External}. Falls back to the default behaviour otherwise.
   */
  @Override
  public boolean hasIgnoreMarker(AnnotatedMember a) {
    if (a.hasAnnotation(External.class)) {
      return true;
    }
    return super.hasIgnoreMarker(a);
  }

}
