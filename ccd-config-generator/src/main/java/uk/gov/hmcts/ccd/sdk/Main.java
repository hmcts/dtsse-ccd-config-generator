package uk.gov.hmcts.ccd.sdk;

import java.io.File;

class Main {

  public static void main(String[] args) {
    File outputDir = new File(args[0]);
    new ConfigGenerator(args[1]).resolveConfig(outputDir);
    // Required on Gradle 4.X or build task hangs.
    System.exit(0);
  }
}
