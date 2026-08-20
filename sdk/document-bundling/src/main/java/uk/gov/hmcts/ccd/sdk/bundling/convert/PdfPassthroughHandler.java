package uk.gov.hmcts.ccd.sdk.bundling.convert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandler;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentHandlingException;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandledDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.HandlerContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;

/**
 * The built-in {@code application/pdf} handler: verifies the spooled source actually loads as an
 * unencrypted PDF and passes the file through unchanged.
 *
 * <p>Verification is deliberate, not decorative — the declared media type is caller-supplied and
 * document stores carry lies. The handler checks the {@code %PDF-} signature within the first
 * kilobyte (the PDF specification permits leading junk), then opens the document with a bounded
 * PDFBox stream cache spilling to the job's temporary directory. Encrypted or password-protected
 * sources are rejected with a typed failure naming the reason: an encrypted evidence page in a
 * merged court bundle is unreadable, and the current stitching service's behaviour of failing
 * deep inside the merge with an opaque error is exactly what this module exists to fix. The
 * source file is never modified; the handled document is the spooled file itself.
 */
public final class PdfPassthroughHandler implements DocumentHandler {

  private static final int SIGNATURE_SCAN_BYTES = 1024;
  private static final byte[] SIGNATURE = {'%', 'P', 'D', 'F', '-'};
  private static final long MAX_CACHE_HEAP_BYTES = 16L * 1024 * 1024;

  @Override
  public HandledDocument handle(ResolvedDocument source, HandlerContext context)
      throws DocumentHandlingException {
    Path file = HandlerFiles.materialise(source, context, ".pdf");
    if (!hasPdfSignature(file)) {
      throw new DocumentHandlingException(
          "The source is not a PDF: no %PDF- signature in the first " + SIGNATURE_SCAN_BYTES
              + " bytes");
    }
    verifyLoadsUnencrypted(file, context);
    return HandledDocument.of(file);
  }

  private static boolean hasPdfSignature(Path file) throws DocumentHandlingException {
    byte[] head = new byte[SIGNATURE_SCAN_BYTES];
    int read;
    try (InputStream in = Files.newInputStream(file)) {
      read = in.readNBytes(head, 0, head.length);
    } catch (IOException e) {
      throw new DocumentHandlingException("Could not read the spooled source file", e);
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

  private static void verifyLoadsUnencrypted(Path file, HandlerContext context)
      throws DocumentHandlingException {
    MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(MAX_CACHE_HEAP_BYTES)
        .setTempDir(scratchDirectory(context));
    try (PDDocument document = Loader.loadPDF(file.toFile(), memory.streamCache)) {
      if (document.isEncrypted()) {
        throw new DocumentHandlingException(
            "The PDF is encrypted; encrypted documents cannot be stitched into a bundle");
      }
    } catch (InvalidPasswordException e) {
      throw new DocumentHandlingException(
          "The PDF is password protected; encrypted documents cannot be stitched into a bundle",
          e);
    } catch (IOException e) {
      throw new DocumentHandlingException(
          "The PDF is corrupt or unreadable: it could not be parsed", e);
    }
  }

  private static java.io.File scratchDirectory(HandlerContext context)
      throws DocumentHandlingException {
    try {
      Path probe = context.createTempFile(".scratch");
      Files.deleteIfExists(probe);
      return probe.getParent().toFile();
    } catch (IOException e) {
      throw new DocumentHandlingException("Could not allocate scratch space for PDF parsing", e);
    }
  }
}
