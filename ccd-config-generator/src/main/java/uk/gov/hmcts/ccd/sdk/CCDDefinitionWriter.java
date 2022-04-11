package uk.gov.hmcts.ccd.sdk;

import java.io.File;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Public API for programmatically generating and exporting definitions.
 */
@Component
public class CCDDefinitionWriter {
  @Autowired
  MultiCaseTypeResolver resolver;

  /**
   * Generate JSON config for all case types into the specified folder.
   */
  public void generateAllCaseTypesToJSON(File destinationFolder) {
    resolver.generateCaseTypes(destinationFolder);
  }
}
