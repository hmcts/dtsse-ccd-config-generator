package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * What send-letter-service does to a letter's documents before uploading them to Xerox, copied
 * from {@code services/pdf/DuplexPreparator} and {@code PdfMerger} (send-letter-service
 * {@code 3121a020}, PDFBox 3.0.8 as here): each document with an odd page count gets a blank
 * page and is re-saved, then several documents are merged and re-saved. Either re-save writes
 * PDF 1.6 with object streams, so a letter only reaches Xerox as PDF/X-1a if neither happens.
 */
public final class SendLetterReplay {

  private SendLetterReplay() {
  }

  /** The bytes send-letter would upload for a letter made of {@code documents}. */
  public static byte[] uploadedBytes(List<byte[]> documents) throws IOException {
    List<byte[]> prepared = new java.util.ArrayList<>();
    for (byte[] document : documents) {
      prepared.add(duplex(document));
    }
    return merge(prepared);
  }

  /**
   * Send-letter's per-document duplicate key, from {@code LetterChecksumService
   * .calculateContentChecksum}: an MD5 over every page's content stream, and nothing else. With
   * the recipients, it decides whether a document was already sent.
   */
  public static String duplicateChecksum(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      MessageDigest md = MessageDigest.getInstance("MD5");
      for (PDPage page : document.getPages()) {
        try (InputStream contents = page.getContents()) {
          md.update(contents.readAllBytes());
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] duplex(byte[] pdf) throws IOException {
    try (var document = Loader.loadPDF(pdf)) {
      int pages = document.getNumberOfPages();
      if (pages % 2 == 1) {
        PDRectangle lastPageMediaBox = document.getPage(pages - 1).getMediaBox();
        document.addPage(new PDPage(lastPageMediaBox));
        var out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
      }
      return pdf;
    }
  }

  private static byte[] merge(List<byte[]> documents) throws IOException {
    if (documents.size() == 1) {
      return documents.getFirst();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PDFMergerUtility merger = new PDFMergerUtility();
    merger.addSources(documents.stream()
        .map(d -> (RandomAccessRead) new RandomAccessReadBuffer(d)).toList());
    merger.setDestinationStream(out);
    merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly().streamCache);
    return out.toByteArray();
  }
}
