package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Builder
@Data
@Jacksonized
@ComplexType(name = "Document", generate = false)
public class Document {

  @JsonProperty("document_url")
  private final String url;

  @JsonProperty("document_filename")
  private final String filename;

  @JsonProperty("document_binary_url")
  private final String binaryUrl;

}
