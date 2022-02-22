package uk.gov.hmcts.ccd.sdk.type;

import static uk.gov.hmcts.ccd.sdk.type.FieldType.FixedList;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

@Data
@NoArgsConstructor
@AllArgsConstructor
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
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private LocalDateTime scannedDate;

  @CCD(
          label = "Delivery date"
  )
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private LocalDateTime deliveryDate;

  @CCD(
          label = "Exception record reference"
  )
  private String exceptionRecordReference;

}
