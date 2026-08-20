package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.type.Document;

class StoredBundleTest {

  @Test
  void mapsOntoTheCcdDocumentComplexType() {
    StoredBundle stored = new StoredBundle(
        "http://dm-store/documents/abc",
        "http://dm-store/documents/abc/binary",
        "hearing-bundle.pdf",
        "application/pdf",
        1024,
        "sha256-hex",
        Optional.of("hash-token"));

    Document document = stored.toDocument();

    assertThat(document.getUrl()).isEqualTo("http://dm-store/documents/abc");
    assertThat(document.getBinaryUrl()).isEqualTo("http://dm-store/documents/abc/binary");
    assertThat(document.getFilename()).isEqualTo("hearing-bundle.pdf");
    assertThat(document.getHashToken()).isEqualTo("hash-token");
    assertThat(document.getCategoryId()).isNull();
  }

  @Test
  void hashTokenIsOptional() {
    StoredBundle stored = new StoredBundle(
        "http://dm-store/documents/abc",
        "http://dm-store/documents/abc/binary",
        "hearing-bundle.pdf",
        "application/pdf",
        1024,
        "sha256-hex",
        Optional.empty());

    assertThat(stored.toDocument().getHashToken()).isNull();
  }
}
