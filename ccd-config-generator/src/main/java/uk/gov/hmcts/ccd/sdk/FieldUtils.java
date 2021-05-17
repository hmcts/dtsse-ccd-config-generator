package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;

public class FieldUtils {

  public static boolean isFieldIgnored(Field field) {
    CCD cf = field.getAnnotation(CCD.class);

    return null != field.getAnnotation(JsonIgnore.class) || (null != cf && cf.ignore());
  }

  public static List<Field> getCaseFields(Class caseDataClass) {
    return ReflectionUtils.getAllFields(caseDataClass)
        .stream()
        .filter(f -> !isFieldIgnored(f))
        .collect(Collectors.toList());
  }
}
