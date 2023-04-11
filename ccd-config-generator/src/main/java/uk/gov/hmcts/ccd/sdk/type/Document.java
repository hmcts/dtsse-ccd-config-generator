package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@NoArgsConstructor
@Builder
@Data
@JsonIgnoreProperties(ignoreUnknown = true) //added temporary to avoid draft doc failing mid events
@ComplexType(name = "Document", generate = false)
public class Document {

  @JsonProperty("document_url")
  private String url;

  @JsonProperty("document_filename")
  private String filename;

  @JsonProperty("document_binary_url")
  private String binaryUrl;

  @JsonProperty("category_id")
  private String categoryId;

  public Document(
      @JsonProperty("document_url") String url,
      @JsonProperty("document_filename") String filename,
      @JsonProperty("document_binary_url") String binaryUrl
  ) {
    this.url = url;
    this.filename = filename;
    this.binaryUrl = binaryUrl;
  }

  @JsonCreator
  public Document(
      @JsonProperty("document_url") String url,
      @JsonProperty("document_filename") String filename,
      @JsonProperty("document_binary_url") String binaryUrl,
      @JsonProperty("category_id") String categoryId
  ) {
    this.url = url;
    this.filename = filename;
    this.binaryUrl = binaryUrl;
    this.categoryId = categoryId;
  }
}
