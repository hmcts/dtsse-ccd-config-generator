package uk.gov.hmcts.ccd.sdk.conversion.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderException;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;

/**
 * An in-process Docmosis that returns a fixed PDF for every conversion and records what it was
 * asked to convert. The real HTTP client is covered by {@code HttpDocmosisRenderServiceTest}
 * against a fake server, and by the opt-in contract test against a real Docmosis.
 */
public final class StubDocmosis implements DocmosisRenderService {

  /** One conversion request. */
  public record Call(String fileName, String mediaType) {
  }

  private final byte[] response;
  private final Path outputDirectory;
  private final DocmosisRenderException failure;
  private final List<Call> calls = new CopyOnWriteArrayList<>();

  private StubDocmosis(byte[] response, Path outputDirectory, DocmosisRenderException failure) {
    this.response = response;
    this.outputDirectory = outputDirectory;
    this.failure = failure;
  }

  /** Answers every conversion with {@code response}. */
  public static StubDocmosis returning(byte[] response, Path outputDirectory) {
    return new StubDocmosis(response, outputDirectory, null);
  }

  /** Fails every conversion. */
  public static StubDocmosis failing(DocmosisRenderException failure) {
    return new StubDocmosis(null, null, failure);
  }

  @Override
  public Path convertToPdf(Path source, String fileName, String mediaType)
      throws DocmosisRenderException {
    calls.add(new Call(fileName, mediaType));
    if (failure != null) {
      throw failure;
    }
    try {
      Files.createDirectories(outputDirectory);
      return Files.write(Files.createTempFile(outputDirectory, "stub-docmosis-", ".pdf"),
          response);
    } catch (IOException e) {
      throw new DocmosisRenderException("stub could not write", false, e);
    }
  }

  /** What was converted, in order. */
  public List<Call> calls() {
    return calls;
  }
}
