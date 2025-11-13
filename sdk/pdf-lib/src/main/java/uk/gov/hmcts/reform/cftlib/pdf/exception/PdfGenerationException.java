package uk.gov.hmcts.reform.cftlib.pdf.exception;

public class PdfGenerationException extends RuntimeException {

  public PdfGenerationException(Throwable cause) {
    super("There was an error during PDF generation", cause);
  }

  public PdfGenerationException(String message, Throwable cause) {
    super(message, cause);
  }
}
