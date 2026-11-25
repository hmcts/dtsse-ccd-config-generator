package uk.gov.hmcts.ccd.sdk.doctoolkit.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleDocument;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleSection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocConversionRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.OutputCondition;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageSize;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfStandard;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintColour;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintOptions;
import uk.gov.hmcts.ccd.sdk.doctoolkit.UploadedDoc;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Watermark;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * The outbox's stored form of a request and of a job's result. The public types carry no
 * serialisation annotations: they are mapped to these records, whose shape is
 * {@link #REQUEST_VERSION}. A change to it needs a new version.
 */
public final class DocRequestJson {
  /** The stored format's version. Versions 1 to 3 were document-bundling's. */
  public static final int REQUEST_VERSION = 4;

  private static final ObjectMapper JSON = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private DocRequestJson() {
  }

  /** A stored job can no longer be read. */
  public static final class Unreadable extends RuntimeException {
    Unreadable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The request as stored.
   *
   * @throws IllegalArgumentException if it has a local file, which another instance cannot read
   */
  public static String write(DocRequest request) {
    return serialise(switch (request) {
      case BundleRequest bundle -> new RequestJson("bundle", bundle.title(), bundle.fileName(),
          section(bundle.root()), bundle.tableOfContents(), bundle.sectionCoverSheets(),
          bundle.documentCoverSheets(), bundle.pageNumbers().name(), bundle.confidentialMarking(),
          bundle.coverPage().map(DocRequestJson::source).orElse(null),
          bundle.missingDocuments().name(), null,
          bundle.watermark().map(DocRequestJson::watermark).orElse(null),
          bundle.print().map(DocRequestJson::print).orElse(null));
      case DocConversionRequest conversion -> new RequestJson("conversion", conversion.title(),
          conversion.fileName(), null, null, null, null, null, null, null, null,
          conversion.parts().stream()
              .map(part -> new PartJson(source(part.source()), part.unchanged())).toList(),
          conversion.watermark().map(DocRequestJson::watermark).orElse(null),
          conversion.print().map(DocRequestJson::print).orElse(null));
    });
  }

  /** The request a stored form describes. */
  public static DocRequest read(String json) {
    RequestJson stored = parse(json, RequestJson.class);
    try {
      return switch (stored.type()) {
        case "bundle" -> bundle(stored);
        case "conversion" -> conversion(stored);
        default -> throw new Unreadable("unknown request type '" + stored.type() + "'", null);
      };
    } catch (IllegalArgumentException e) {
      throw new Unreadable("the stored request is invalid: " + e.getMessage(), e);
    }
  }

  /** A job's result as stored. */
  public static String writeResult(UploadedDoc uploaded) {
    Document document = uploaded.document();
    return serialise(new ResultJson(document.getUrl(), document.getBinaryUrl(),
        document.getFilename(), document.getUploadTimestamp() == null ? null
            : document.getUploadTimestamp().toString(), uploaded.pageCount(),
        uploaded.failures()));
  }

  /** The result a stored form describes. */
  public static UploadedDoc readResult(String json) {
    ResultJson stored = parse(json, ResultJson.class);
    return new UploadedDoc(Document.builder().url(stored.url()).binaryUrl(stored.binaryUrl())
        .filename(stored.filename())
        .uploadTimestamp(stored.uploadTimestamp() == null ? null
            : LocalDateTime.parse(stored.uploadTimestamp()))
        .build(), stored.pageCount(), stored.failures() == null ? List.of() : stored.failures());
  }

  /** Documents that could not be included, as stored. */
  public static String writeFailures(List<DocFailure> failures) {
    return serialise(failures);
  }

  /** The stored documents that could not be included. */
  public static List<DocFailure> readFailures(String json) {
    if (json == null) {
      return List.of();
    }
    return List.of(parse(json, DocFailure[].class));
  }

  private static BundleRequest bundle(RequestJson stored) {
    BundleRequest.Builder builder = BundleRequest.builder(stored.title())
        .fileName(stored.fileName())
        .tableOfContents(stored.tableOfContents())
        .sectionCoverSheets(stored.sectionCoverSheets())
        .documentCoverSheets(stored.documentCoverSheets())
        .pageNumbers(PageNumbers.valueOf(stored.pageNumbers()))
        .confidentialMarking(stored.confidentialMarking())
        .missingDocuments(MissingDocuments.valueOf(stored.missingDocuments()));
    stored.root().documents().forEach(document -> builder.document(document(document)));
    stored.root().sections().forEach(section -> builder.section(section(section)));
    if (stored.coverPage() != null) {
      builder.coverPage(source(stored.coverPage()));
    }
    if (stored.watermark() != null) {
      builder.watermark(watermark(stored.watermark()));
    }
    if (stored.print() != null) {
      builder.print(print(stored.print()));
    }
    return builder.build();
  }

  private static DocConversionRequest conversion(RequestJson stored) {
    DocConversionRequest.Builder builder = null;
    for (PartJson part : stored.parts()) {
      DocSource source = source(part.source());
      if (builder == null) {
        builder = part.unchanged() ? DocConversionRequest.unchanged(source)
            : DocConversionRequest.document(source);
      } else if (part.unchanged()) {
        builder.unchanged(source);
      } else {
        builder.document(source);
      }
    }
    if (builder == null) {
      throw new IllegalArgumentException("a conversion has no documents");
    }
    builder.title(stored.title()).fileName(stored.fileName());
    if (stored.watermark() != null) {
      builder.watermark(watermark(stored.watermark()));
    }
    if (stored.print() != null) {
      builder.print(print(stored.print()));
    }
    return builder.build();
  }

  private static SectionJson section(BundleSection section) {
    return new SectionJson(section.title(), section.placeholderWhenEmpty(),
        section.documents().stream().map(DocRequestJson::document).toList(),
        section.sections().stream().map(DocRequestJson::section).toList());
  }

  private static BundleSection section(SectionJson stored) {
    BundleSection.Builder builder = BundleSection.builder(stored.title());
    stored.documents().forEach(document -> builder.document(document(document)));
    stored.sections().forEach(section -> builder.section(section(section)));
    if (stored.placeholderWhenEmpty()) {
      builder.placeholderWhenEmpty();
    }
    return builder.build();
  }

  private static DocumentJson document(BundleDocument document) {
    return new DocumentJson(document.title(), document.date().map(LocalDate::toString).orElse(null),
        document.confidential(), source(document.source()));
  }

  private static BundleDocument document(DocumentJson stored) {
    return BundleDocument.builder(source(stored.source()))
        .title(stored.title())
        .date(stored.date() == null ? null : LocalDate.parse(stored.date()))
        .confidential(stored.confidential())
        .build();
  }

  private static SourceJson source(DocSource source) {
    return switch (source) {
      // name() falls back to the id; store only a name the caller gave.
      case DocSource.Cdam cdam -> new SourceJson("cdam", cdam.documentId(),
          cdam.name().equals(cdam.documentId().toString()) ? null : cdam.name(), null, null, null,
          null, null);
      case DocSource.Bytes bytes -> new SourceJson("bytes", null, bytes.name(),
          Base64.getEncoder().encodeToString(bytes.content()), bytes.mediaType(), null, null, null);
      case HtmlPage page ->
          new SourceJson("html", null, null, null, null, page.title(), page.body(), page.css());
      case DocSource.LocalFile file -> throw new IllegalArgumentException(file.name() + " is a"
          + " local file, which the instance running the job may not have. Queue its content"
          + " with DocSource.bytes(...), or a CDAM id.");
    };
  }

  private static DocSource source(SourceJson stored) {
    return switch (stored.kind()) {
      case "cdam" -> DocSource.cdam(stored.cdam(), stored.name());
      case "bytes" -> DocSource.bytes(Base64.getDecoder().decode(stored.content()), stored.name(),
          stored.mediaType());
      case "html" -> HtmlPage.builder().title(stored.title()).body(stored.html())
          .css(stored.css()).build();
      default -> throw new IllegalArgumentException("unknown source kind '" + stored.kind() + "'");
    };
  }

  private static WatermarkJson watermark(Watermark watermark) {
    return new WatermarkJson(Base64.getEncoder().encodeToString(watermark.image()),
        watermark.firstPageOnly(), watermark.behindContent());
  }

  private static Watermark watermark(WatermarkJson stored) {
    Watermark watermark = Watermark.image(Base64.getDecoder().decode(stored.image()));
    watermark = stored.firstPageOnly() ? watermark.onFirstPageOnly() : watermark;
    return stored.behindContent() ? watermark.underContent() : watermark;
  }

  private static PrintJson print(PrintOptions print) {
    OutputCondition condition = print.outputCondition();
    return new PrintJson(print.pageSize().name(), print.marginMillimetres(),
        print.resolutionDpi(), print.colour().name(), print.standard().name(),
        new ConditionJson(condition.identifier(), condition.info(), condition.registryName()),
        print.rotateLandscapePages(), print.padToEvenPages(), print.maxOutputBytes(),
        print.greyscaleAboveFraction());
  }

  private static PrintOptions print(PrintJson stored) {
    ConditionJson condition = stored.outputCondition();
    return PrintOptions.builder()
        .pageSize(PageSize.valueOf(stored.pageSize()))
        .marginMillimetres(stored.marginMillimetres())
        .resolutionDpi(stored.resolutionDpi())
        .colour(PrintColour.valueOf(stored.colour()))
        .standard(PdfStandard.valueOf(stored.standard()))
        .outputCondition(new OutputCondition(condition.identifier(), condition.info(),
            condition.registryName()))
        .rotateLandscapePages(stored.rotateLandscapePages())
        .padToEvenPages(stored.padToEvenPages())
        .maxOutputBytes(stored.maxOutputBytes())
        .greyscaleAboveFraction(stored.greyscaleAboveFraction())
        .build();
  }

  private static String serialise(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
    }
  }

  private static <T> T parse(String json, Class<T> type) {
    try {
      return JSON.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new Unreadable("it is not a stored " + type.getSimpleName() + ": "
          + e.getOriginalMessage(), e);
    }
  }

  record RequestJson(String type, String title, String fileName, SectionJson root,
      Boolean tableOfContents, Boolean sectionCoverSheets, Boolean documentCoverSheets,
      String pageNumbers, Boolean confidentialMarking, SourceJson coverPage,
      String missingDocuments, List<PartJson> parts, WatermarkJson watermark, PrintJson print) {
  }

  record SectionJson(String title, boolean placeholderWhenEmpty, List<DocumentJson> documents,
      List<SectionJson> sections) {
  }

  record DocumentJson(String title, String date, boolean confidential, SourceJson source) {
  }

  record SourceJson(String kind, UUID cdam, String name, String content, String mediaType,
      String title, String html, String css) {
  }

  record PartJson(SourceJson source, boolean unchanged) {
  }

  record WatermarkJson(String image, boolean firstPageOnly, boolean behindContent) {
  }

  record ConditionJson(String identifier, String info, String registryName) {
  }

  record PrintJson(String pageSize, double marginMillimetres, int resolutionDpi, String colour,
      String standard, ConditionJson outputCondition, boolean rotateLandscapePages,
      boolean padToEvenPages, long maxOutputBytes, double greyscaleAboveFraction) {
  }

  record ResultJson(String url, String binaryUrl, String filename, String uploadTimestamp,
      int pageCount, List<DocFailure> failures) {
  }
}
