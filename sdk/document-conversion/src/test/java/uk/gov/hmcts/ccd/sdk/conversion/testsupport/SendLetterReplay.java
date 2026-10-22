package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
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
