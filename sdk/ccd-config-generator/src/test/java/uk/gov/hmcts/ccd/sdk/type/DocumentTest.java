package uk.gov.hmcts.ccd.sdk.type;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class DocumentTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void roundTripsDocumentHash() throws Exception {
    Document document = mapper.readValue("""
        {
          "document_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111",
          "document_filename": "document.pdf",
          "document_binary_url": "http://dm-store/documents/11111111-1111-1111-1111-111111111111/binary",
          "document_hash": "hash-token"
        }
        """, Document.class);

    assertThat(document.getHashToken()).isEqualTo("hash-token");

    String json = mapper.writeValueAsString(document);

    assertThat(json).contains("\"document_hash\":\"hash-token\"");
  }
}
