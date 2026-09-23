package uk.gov.hmcts.ccd.sdk.testing;

import java.util.Arrays;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

/** Imports only the application classes named by a focused SDK test. */
public class CcdSdkTestImportSelector implements ImportSelector {

  @Override
  public String[] selectImports(AnnotationMetadata metadata) {
    var attributes = metadata.getAnnotationAttributes(CcdSdkTest.class.getName());
    if (attributes == null) {
      throw new IllegalStateException("@CcdSdkTest attributes are unavailable");
    }
    Class<?>[] classes = (Class<?>[]) attributes.get("components");
    return Arrays.stream(classes).map(Class::getName).toArray(String[]::new);
  }
}
