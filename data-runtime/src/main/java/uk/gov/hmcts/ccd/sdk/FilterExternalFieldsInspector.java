package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

public class FilterExternalFieldsInspector extends JacksonAnnotationIntrospector {

    @Override
    public boolean hasIgnoreMarker(AnnotatedMember a) {
        if (a.hasAnnotation(External.class)) {
            return true;
        }
        return super.hasIgnoreMarker(a);
    }

}
