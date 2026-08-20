package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleArtifact;

/**
 * A byte-array backed {@link BundleArtifact} for adapter tests.
 */
final class InMemoryBundleArtifact implements BundleArtifact {

  private final String fileName;
  private final byte[] content;

  InMemoryBundleArtifact(String fileName, byte[] content) {
    this.fileName = fileName;
    this.content = content;
  }

  @Override
  public String fileName() {
    return fileName;
  }

  @Override
  public String mediaType() {
    return "application/pdf";
  }

  @Override
  public long size() {
    return content.length;
  }

  @Override
  public String sha256() {
    return "test-sha256";
  }

  @Override
  public int pageCount() {
    return 1;
  }

  @Override
  public InputStream open() {
    return new ByteArrayInputStream(content);
  }
}
