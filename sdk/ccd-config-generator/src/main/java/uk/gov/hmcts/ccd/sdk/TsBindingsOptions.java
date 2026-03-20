package uk.gov.hmcts.ccd.sdk;

import java.io.File;

public record TsBindingsOptions(boolean enabled, File outputDir, String moduleName) {

  private static final String ENABLED_KEY = "ccd.tsBindings.enabled";
  private static final String OUTPUT_DIR_KEY = "ccd.tsBindings.outputDir";
  private static final String MODULE_NAME_KEY = "ccd.tsBindings.moduleName";

  static TsBindingsOptions fromSystemProperties() {
    boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_KEY, "false"));
    String outputDir = System.getProperty(OUTPUT_DIR_KEY);
    String moduleName = System.getProperty(MODULE_NAME_KEY, "ccd-bindings");

    return new TsBindingsOptions(enabled, outputDir == null ? null : new File(outputDir), moduleName);
  }
}
