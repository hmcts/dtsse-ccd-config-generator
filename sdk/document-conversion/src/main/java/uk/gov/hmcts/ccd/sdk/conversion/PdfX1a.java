package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Calendar;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

/**
 * The document-level parts of PDF/X-1a:2001 (ISO 15930-1). Page content is made conformant by
 * {@link PrintRenderer}; this class adds the identification, the output intent and the file
 * structure the standard requires.
 */
final class PdfX1a {
  static final String VERSION = "PDF/X-1:2001";
  static final String CONFORMANCE = "PDF/X-1a:2001";
  static final String CREATOR = "HMCTS CCD SDK document-conversion";

  private static final SecureRandom RANDOM = new SecureRandom();

  private PdfX1a() {
  }

  /**
   * Identifies the document as PDF/X-1a:2001, after {@link #describe}.
   */
  static void identify(PDDocument document, String title, OutputCondition condition) {
    describe(document, title);
    // PDF/X-1a:2001 is built on PDF 1.3. PDDocument.setVersion refuses to lower the version,
    // so set the header version on the COS document directly.
    document.getDocument().setVersion(1.3f);
    // A catalog /Version (PDF 1.4) overrides the header; merging can raise one from a part.
    document.getDocumentCatalog().getCOSObject().removeItem(COSName.VERSION);

    PDDocumentInformation info = document.getDocumentInformation();
    info.setCustomMetadataValue("GTS_PDFXVersion", VERSION);
    info.setCustomMetadataValue("GTS_PDFXConformance", CONFORMANCE);

    COSDictionary intent = new COSDictionary();
    intent.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
    intent.setItem(COSName.S, COSName.getPDFName("GTS_PDFX"));
    intent.setString(COSName.getPDFName("OutputConditionIdentifier"), condition.identifier());
    intent.setString(COSName.getPDFName("OutputCondition"), condition.info());
    intent.setString(COSName.INFO, condition.info());
    intent.setString(COSName.getPDFName("RegistryName"), condition.registryName());
    COSArray intents = new COSArray();
    intents.add(intent);
    document.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
  }

  /**
   * The document information and trailer ID PDF/X-1a requires, without claiming conformance:
   * for a letter whose as-is pages may not conform.
   */
  static void describe(PDDocument document, String title) {
    PDDocumentInformation info = document.getDocumentInformation();
    // Merged print-ready parts bring their own identification; only identify() may add it back.
    info.getCOSObject().removeItem(COSName.getPDFName("GTS_PDFXVersion"));
    info.getCOSObject().removeItem(COSName.getPDFName("GTS_PDFXConformance"));
    document.getDocumentCatalog().getCOSObject().removeItem(COSName.OUTPUT_INTENTS);
    Calendar now = Calendar.getInstance();
    info.setTitle(title);
    info.setCreator(CREATOR);
    info.setProducer(CREATOR);
    info.setCreationDate(now);
    info.setModificationDate(now);
    info.setTrapped("False");

    byte[] id = new byte[16];
    RANDOM.nextBytes(id);
    COSArray ids = new COSArray();
    ids.add(new COSString(id));
    ids.add(new COSString(id));
    document.getDocument().getTrailer().setItem(COSName.ID, ids);
  }

  /** Saves with a classic cross-reference table: object and xref streams are PDF 1.5. */
  static void save(PDDocument document, Path output) throws IOException {
    document.save(output.toFile(), CompressParameters.NO_COMPRESSION);
  }
}
