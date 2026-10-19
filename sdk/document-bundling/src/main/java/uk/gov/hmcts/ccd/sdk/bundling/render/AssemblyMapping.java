package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleSection;
import uk.gov.hmcts.ccd.sdk.bundling.api.EmptySectionPolicy;
import uk.gov.hmcts.ccd.sdk.bundling.api.WatermarkPreset;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyContent;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyFolder;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.EmptySectionPage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.MissingDocumentPage;
import uk.gov.hmcts.ccd.sdk.bundling.pdf.Watermark;

final class AssemblyMapping {
  record Origin(BundleDocument document) {
  }

  record Mapped(AssemblyRequest request, List<Origin> origins) {
  }

  private AssemblyMapping() {
  }

  static Mapped map(BundleRequest request, Map<String, AssemblyContent> handledPdfs,
      Optional<Path> coverPage, Map<String, Path> watermarkImages) {
    List<Origin> origins = new ArrayList<>();
    List<AssemblyNode> items = mapChildren(request.root(), handledPdfs, origins);
    if (items.isEmpty()
        && request.root().emptySectionPolicy() == EmptySectionPolicy.INCLUDE_PLACEHOLDER) {
      origins.add(new Origin(null));
      items = List.of(new AssemblyItem(
          request.root().title(), Optional.empty(), false, new EmptySectionPage()));
    }
    Optional<Watermark> watermark = request.presentation().watermark()
        .map(preset -> resolve(preset, watermarkImages));
    AssemblyRequest assembly = new AssemblyRequest(
        request.title(),
        request.fileName(),
        Optional.empty(),
        request.presentation(),
        true,
        coverPage,
        watermark,
        items);
    return new Mapped(assembly, List.copyOf(origins));
  }

  private static Watermark resolve(WatermarkPreset preset, Map<String, Path> watermarkImages) {
    Path image = watermarkImages.get(preset.imageName());
    if (image == null) {
      throw new IllegalArgumentException("No watermark image is registered under '"
          + preset.imageName() + "'; registered: " + watermarkImages.keySet());
    }
    return new Watermark(image, preset.scope(), preset.rendering());
  }

  private static List<AssemblyNode> mapChildren(
      BundleSection section, Map<String, AssemblyContent> handledPdfs, List<Origin> origins) {
    List<AssemblyNode> nodes = new ArrayList<>();
    for (BundleDocument document : section.documents()) {
      AssemblyContent content = handledPdfs.get(document.id());
      nodes.add(new AssemblyItem(
          content instanceof MissingDocumentPage ? document.title() + " (missing)" : document.title(),
          document.date(),
          document.confidential(),
          content));
      origins.add(new Origin(document));
    }
    for (BundleSection child : section.sections()) {
      mapSection(child, handledPdfs, origins).ifPresent(nodes::add);
    }
    return nodes;
  }

  private static Optional<AssemblyNode> mapSection(
      BundleSection section, Map<String, AssemblyContent> handledPdfs, List<Origin> origins) {
    List<Origin> childOrigins = new ArrayList<>();
    List<AssemblyNode> children = mapChildren(section, handledPdfs, childOrigins);
    if (!children.isEmpty()) {
      origins.addAll(childOrigins);
      return Optional.of(new AssemblyFolder(section.title(), children));
    }
    if (section.emptySectionPolicy() == EmptySectionPolicy.INCLUDE_PLACEHOLDER) {
      origins.add(new Origin(null));
      return Optional.of(new AssemblyItem(
          section.title(), Optional.empty(), false, new EmptySectionPage()));
    }
    return Optional.empty();
  }
}
