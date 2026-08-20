package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;

/**
 * Validation of the internal assembly model.
 */
class AssemblyModelTest {

  private final AssemblyItem item = new AssemblyItem("Doc", Optional.empty(), false,
      new PdfSource(Path.of("doc.pdf")));

  @Test
  void requestRejectsBlankTitle() {
    assertThatThrownBy(() -> AssemblyRequest.of(" ", "out.pdf",
        BundlePresentation.courtDefault(), List.of(item)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bundleTitle");
  }

  @Test
  void requestRejectsFileNamesWithPathSeparatorsOrTraversal() {
    for (String bad : new String[] {"a/b.pdf", "a\\b.pdf", "..pdf"}) {
      assertThatThrownBy(() -> AssemblyRequest.of("Bundle", bad,
          BundlePresentation.courtDefault(), List.of(item)))
          .as("file name %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("plain file name");
    }
  }

  @Test
  void requestCopiesItsItemTreeDefensively() {
    var mutable = new java.util.ArrayList<AssemblyNode>(List.of(item));
    AssemblyRequest request = AssemblyRequest.of("Bundle", "out.pdf",
        BundlePresentation.courtDefault(), mutable);

    mutable.clear();

    assertThat(request.items()).hasSize(1);
    assertThatThrownBy(() -> request.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void folderCopiesItsChildrenDefensively() {
    var mutable = new java.util.ArrayList<AssemblyNode>(List.of(item));
    AssemblyFolder folder = new AssemblyFolder("Folder", mutable);

    mutable.clear();

    assertThat(folder.children()).hasSize(1);
  }

  @Test
  void itemRequiresTitleAndContent() {
    assertThatThrownBy(() -> new AssemblyItem("", Optional.empty(), false,
        new EmptySectionPage()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AssemblyItem("Doc", Optional.empty(), false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void watermarkFactoriesValidateTheirInput() {
    assertThatThrownBy(() -> Watermark.image(null, Watermark.Scope.ALL_PAGES,
        Watermark.Rendering.OPAQUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Watermark.text(" ", Watermark.Scope.ALL_PAGES))
        .isInstanceOf(IllegalArgumentException.class);

    Watermark text = Watermark.text("COPY", Watermark.Scope.FIRST_PAGE);
    assertThat(text.text()).contains("COPY");
    assertThat(text.image()).isEmpty();
    assertThat(text.rendering()).isEqualTo(Watermark.Rendering.TRANSLUCENT);
  }

  @Test
  void mediaLinkPageRequiresMediaTypeAndPlaceholder() {
    assertThatThrownBy(() -> new MediaLinkPage(" ", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void assembledItemRejectsNonPositivePlacement() {
    assertThatThrownBy(() -> new AssembledItem("Doc", 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AssembledItem("Doc", 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
