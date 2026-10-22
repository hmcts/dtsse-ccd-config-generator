package uk.gov.hmcts.ccd.sdk.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import uk.gov.hmcts.ccd.sdk.conversion.ConversionException.Reason;

/** Checks that a file is a PDF that can be opened and has pages. */
public final class PdfChecks {
  static final long MAX_CACHE_HEAP_BYTES = 16L * 1024 * 1024;

  private static final int SIGNATURE_SCAN_BYTES = 1024;
  private static final byte[] SIGNATURE = {'%', 'P', 'D', 'F', '-'};

  private PdfChecks() {
  }

  /**
   * Requires a {@code %PDF-} signature in the first kilobyte, a parseable unencrypted document,
   * and at least one page.
   *
   * @param scratchDirectory where PDFBox may spill parsed content
   * @return the page count
   * @throws ConversionException with {@link Reason#INVALID_CONTENT} or {@link Reason#ENCRYPTED}
   */
  public static int requireUsablePdf(Path file, Path scratchDirectory)
      throws ConversionException {
    if (!hasPdfSignature(file)) {
      throw new ConversionException(Reason.INVALID_CONTENT,
          "The source is not a PDF: no %PDF- signature in the first " + SIGNATURE_SCAN_BYTES
              + " bytes");
    }
    try (PDDocument document = load(file, scratchDirectory)) {
      if (document.isEncrypted()) {
        throw new ConversionException(Reason.ENCRYPTED, "The PDF is encrypted");
      }
      if (document.getNumberOfPages() == 0) {
        throw new ConversionException(Reason.INVALID_CONTENT, "The PDF has no pages");
      }
      return document.getNumberOfPages();
    } catch (InvalidPasswordException e) {
      throw new ConversionException(Reason.ENCRYPTED, "The PDF is password protected", e);
    } catch (IOException e) {
      throw new ConversionException(Reason.INVALID_CONTENT,
          "The PDF is corrupt or unreadable: it could not be parsed", e);
    }
  }

  static PDDocument load(Path file, Path scratchDirectory) throws IOException {
    MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(MAX_CACHE_HEAP_BYTES)
        .setTempDir(scratchDirectory.toFile());
    return Loader.loadPDF(file.toFile(), memory.streamCache);
  }

  private static boolean hasPdfSignature(Path file) throws ConversionException {
    byte[] head = new byte[SIGNATURE_SCAN_BYTES];
    int read;
    try (InputStream in = Files.newInputStream(file)) {
      read = in.readNBytes(head, 0, head.length);
    } catch (IOException e) {
      throw new ConversionException(Reason.IO_FAILURE, "Could not read the source file", e);
    }
    for (int i = 0; i + SIGNATURE.length <= read; i++) {
      int j = 0;
      while (j < SIGNATURE.length && head[i + j] == SIGNATURE[j]) {
        j++;
      }
      if (j == SIGNATURE.length) {
        return true;
      }
    }
    return false;
  }
}
