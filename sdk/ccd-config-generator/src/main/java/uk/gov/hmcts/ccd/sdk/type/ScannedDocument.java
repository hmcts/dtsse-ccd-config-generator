package uk.gov.hmcts.ccd.sdk.type;

import static uk.gov.hmcts.ccd.sdk.type.FieldType.FixedList;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.deserializer.LocalDateTimeDeserializer;

@Data
@NoArgsConstructor
@Builder
public class ScannedDocument {

  @CCD(
          label = "Select document type",
          typeOverride = FixedList,
          typeParameterOverride = "ScannedDocumentType"
  )
  private ScannedDocumentType type;

  @CCD(
          label = "Document subtype"
  )
  private String subtype;

  @CCD(
          label = "Scanned document url"
  )
  private Document url;

  @CCD(
          label = "Document control number"
  )
  private String controlNumber;

  @CCD(
          label = "File Name"
  )
  private String fileName;

  @CCD(
          label = "Scanned date"
  )
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private LocalDateTime scannedDate;

  @CCD(
          label = "Delivery date"
  )
  @JsonDeserialize(using = LocalDateTimeDeserializer.class)
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private LocalDateTime deliveryDate;

  @CCD(
          label = "Exception record reference"
  )
  private String exceptionRecordReference;

  @JsonCreator
  public ScannedDocument(@JsonProperty("type") ScannedDocumentType type,
                         @JsonProperty("subtype") String subtype,
                         @JsonProperty("url") Document url,
                         @JsonProperty("controlNumber") String controlNumber,
                         @JsonProperty("fileName") String fileName,
                         @JsonProperty("scannedDate") LocalDateTime scannedDate,
                         @JsonProperty("deliveryDate") LocalDateTime deliveryDate,
                         @JsonProperty("exceptionRecordReference") String exceptionRecordReference) {
    this.type = type;
    this.subtype = subtype;
    this.url = url;
    this.controlNumber = controlNumber;
    this.fileName = fileName;
    this.scannedDate = scannedDate;
    this.deliveryDate = deliveryDate;
    this.exceptionRecordReference = exceptionRecordReference;
  }
}
