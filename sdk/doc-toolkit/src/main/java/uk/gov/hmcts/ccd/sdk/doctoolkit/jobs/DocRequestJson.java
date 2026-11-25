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
import java.util.Optional;
import java.util.OptionalInt;
import uk.gov.hmcts.ccd.sdk.doctoolkit.Classification;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.OutputCondition;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageNumbers;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PageSize;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfSection;
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
  /** The stored format's version. Versions 1 to 4 were earlier formats. */
  public static final int REQUEST_VERSION = 5;

  /**
   * The version of how the toolkit renders a request. It is part of every stored request, so
   * bumping it (for a change to layout, page numbering or conversion) changes every fingerprint
   * and re-renders each kept-up-to-date document on its case's next event.
   */
  public static final int RENDERING_VERSION = 1;

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
   * The request as stored. The same request always gives the same JSON.
   *
   * @throws IllegalArgumentException if it has a local file, which another instance cannot read
   */
  public static String write(PdfRequest request) {
    return serialise(new RequestJson(RENDERING_VERSION, request.title(), request.fileName(),
        section(request.root()), request.coverPage().map(DocRequestJson::source).orElse(null),
        request.titlePage(), request.tableOfContents(), request.sectionCoverSheets(),
        request.documentCoverSheets(), request.pageNumbers().name(),
        request.confidentialMarking(),
        request.watermark().map(DocRequestJson::watermark).orElse(null),
        request.missingDocuments().name(),
        request.print().map(DocRequestJson::print).orElse(null),
        request.category().orElse(null), request.classification().name()));
  }

  /** The request a stored form describes. */
  public static PdfRequest read(String json) {
    RequestJson stored = parse(json, RequestJson.class);
    try {
      PdfRequest.Builder builder = PdfRequest.builder(stored.title())
          .fileName(stored.fileName())
          .pageNumbers(PageNumbers.valueOf(stored.pageNumbers()))
          .missingDocuments(MissingDocuments.valueOf(stored.missingDocuments()))
          .classification(Classification.valueOf(stored.classification()));
      stored.root().parts().forEach(part -> builder.document(part(part)));
      stored.root().sections().forEach(section -> builder.section(section(section)));
      if (stored.coverPage() != null) {
        builder.coverPage(source(stored.coverPage()));
      }
      if (stored.titlePage()) {
        builder.titlePage();
      }
      if (stored.tableOfContents()) {
        builder.tableOfContents();
      }
      if (stored.sectionCoverSheets()) {
        builder.sectionCoverSheets();
      }
      if (stored.documentCoverSheets()) {
        builder.documentCoverSheets();
      }
      if (stored.confidentialMarking()) {
        builder.confidentialMarking();
      }
      if (stored.watermark() != null) {
        builder.watermark(watermark(stored.watermark()));
      }
      if (stored.print() != null) {
        builder.print(print(stored.print()));
      }
      if (stored.category() != null) {
        builder.category(stored.category());
      }
      return builder.build();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new Unreadable("the stored request is invalid: " + e.getMessage(), e);
    }
  }

  /** A job's result as stored. */
  public static String writeResult(UploadedDoc uploaded) {
    Document document = uploaded.document();
    return serialise(new ResultJson(document.getUrl(), document.getBinaryUrl(),
        document.getFilename(), document.getCategoryId(),
        document.getUploadTimestamp() == null ? null : document.getUploadTimestamp().toString(),
        uploaded.pageCount(), failures(uploaded.failures())));
  }

  /** The result a stored form describes. */
  public static UploadedDoc readResult(String json) {
    ResultJson stored = parse(json, ResultJson.class);
    return new UploadedDoc(Document.builder().url(stored.url()).binaryUrl(stored.binaryUrl())
        .filename(stored.filename())
        .categoryId(stored.categoryId())
        .uploadTimestamp(stored.uploadTimestamp() == null ? null
            : LocalDateTime.parse(stored.uploadTimestamp()))
        .build(), stored.pageCount(), failures(stored.failures()));
  }

  /** Documents that could not be included, as stored. */
  public static String writeFailures(List<DocFailure> failures) {
    return serialise(failures(failures));
  }

  /** The stored documents that could not be included. */
  public static List<DocFailure> readFailures(String json) {
    if (json == null) {
      return List.of();
    }
    return failures(List.of(parse(json, FailureJson[].class)));
  }

  private static List<FailureJson> failures(List<DocFailure> failures) {
    return failures.stream().map(failure -> new FailureJson(failure.id().orElse(null),
        failure.title(), failure.sectionPath(),
        failure.page().isPresent() ? failure.page().getAsInt() : null, failure.reason().name(),
        failure.detail())).toList();
  }

  private static List<DocFailure> failures(Iterable<FailureJson> stored) {
    List<DocFailure> failures = new java.util.ArrayList<>();
    if (stored == null) {
      return failures;
    }
    for (FailureJson failure : stored) {
      failures.add(new DocFailure(Optional.ofNullable(failure.id()), failure.title(),
          failure.sectionPath() == null ? List.of() : failure.sectionPath(),
          failure.page() == null ? OptionalInt.empty() : OptionalInt.of(failure.page()),
          DocFailure.Reason.valueOf(failure.reason()), failure.detail()));
    }
    return List.copyOf(failures);
  }

  private static SectionJson section(PdfSection section) {
    return new SectionJson(section.title(), section.placeholderWhenEmpty(),
        section.parts().stream().map(DocRequestJson::part).toList(),
        section.sections().stream().map(DocRequestJson::section).toList());
  }

  private static PdfSection section(SectionJson stored) {
    PdfSection.Builder builder = PdfSection.builder(stored.title());
    stored.parts().forEach(part -> builder.document(part(part)));
    stored.sections().forEach(section -> builder.section(section(section)));
    if (stored.placeholderWhenEmpty()) {
      builder.placeholderWhenEmpty();
    }
    return builder.build();
  }

  private static PartJson part(PdfPart part) {
    return new PartJson(part.id().orElse(null), part.title(),
        part.date().map(LocalDate::toString).orElse(null), part.confidential(),
        part.unchanged(), source(part.source()));
  }

  private static PdfPart part(PartJson stored) {
    PdfPart.Builder builder = PdfPart.builder(source(stored.source()))
        .id(stored.id())
        .title(stored.title())
        .date(stored.date() == null ? null : LocalDate.parse(stored.date()));
    if (stored.confidential()) {
      builder.confidential();
    }
    if (stored.unchanged()) {
      builder.unchanged();
    }
    return builder.build();
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

  record RequestJson(int renderingVersion, String title, String fileName, SectionJson root,
      SourceJson coverPage, boolean titlePage, boolean tableOfContents,
      boolean sectionCoverSheets, boolean documentCoverSheets, String pageNumbers,
      boolean confidentialMarking, WatermarkJson watermark, String missingDocuments,
      PrintJson print, String category, String classification) {
  }

  record SectionJson(String title, boolean placeholderWhenEmpty, List<PartJson> parts,
      List<SectionJson> sections) {
  }

  record PartJson(String id, String title, String date, boolean confidential, boolean unchanged,
      SourceJson source) {
  }

  record SourceJson(String kind, UUID cdam, String name, String content, String mediaType,
      String title, String html, String css) {
  }

  record WatermarkJson(String image, boolean firstPageOnly, boolean behindContent) {
  }

  record ConditionJson(String identifier, String info, String registryName) {
  }

  record PrintJson(String pageSize, double marginMillimetres, int resolutionDpi, String colour,
      String standard, ConditionJson outputCondition, boolean rotateLandscapePages,
      boolean padToEvenPages, long maxOutputBytes, double greyscaleAboveFraction) {
  }

  record ResultJson(String url, String binaryUrl, String filename, String categoryId,
      String uploadTimestamp, int pageCount, List<FailureJson> failures) {
  }

  record FailureJson(String id, String title, List<String> sectionPath, Integer page,
      String reason, String detail) {
  }
}
