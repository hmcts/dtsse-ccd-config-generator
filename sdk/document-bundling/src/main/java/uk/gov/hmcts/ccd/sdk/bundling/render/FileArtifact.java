package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;

record FileArtifact(Path file, String fileName, long size, String sha256, int pageCount)
    implements BundleArtifact {
  @Override
  public String mediaType() {
    return "application/pdf";
  }

  @Override
  public InputStream open() throws IOException {
    return Files.newInputStream(file);
  }
}
