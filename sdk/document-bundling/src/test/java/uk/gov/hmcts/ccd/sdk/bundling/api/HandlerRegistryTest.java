package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class HandlerRegistryTest {

  private static DocumentHandler handler() {
    return (source, context) -> HandledDocument.of(Path.of("unused.pdf"));
  }

  private static BundlingExtension extension(String name, Consumer<BundlingExtensionContext> configure) {
    return new BundlingExtension() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void configure(BundlingExtensionContext context) {
        configure.accept(context);
      }
    };
  }

  @Test
  void addHandlerRegistersANewMediaType() {
    DocumentHandler msgHandler = handler();
    HandlerRegistry registry = HandlerRegistry.create(
        Map.of("application/pdf", handler()),
        List.of(extension("et", context ->
            context.addHandler("application/vnd.ms-outlook", msgHandler))));

    assertThat(registry.handlerFor("application/vnd.ms-outlook")).contains(msgHandler);
    assertThat(registry.handledMediaTypes())
        .containsExactlyInAnyOrder("application/pdf", "application/vnd.ms-outlook");
  }

  @Test
  void addHandlerFailsFastWhenTheTypeIsAlreadyHandledNamingExtensionAndType() {
    assertThatThrownBy(() -> HandlerRegistry.create(
        Map.of("application/pdf", handler()),
        List.of(extension("et-media-pages", context ->
            context.addHandler("application/pdf", handler())))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("et-media-pages")
        .hasMessageContaining("application/pdf")
        .hasMessageContaining("replaceHandler");
  }

  @Test
  void replaceHandlerOverridesABuiltIn() {
    DocumentHandler replacement = handler();
    HandlerRegistry registry = HandlerRegistry.create(
        Map.of("video/mp4", handler()),
        List.of(extension("et", context -> context.replaceHandler("video/mp4", replacement))));

    assertThat(registry.handlerFor("video/mp4")).contains(replacement);
  }

  @Test
  void replaceHandlerFailsFastWhenTheTypeIsNotHandled() {
    assertThatThrownBy(() -> HandlerRegistry.create(
        Map.of("application/pdf", handler()),
        List.of(extension("et", context -> context.replaceHandler("video/mp4", handler())))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("et")
        .hasMessageContaining("video/mp4")
        .hasMessageContaining("addHandler");
  }

  @Test
  void removeHandlerRevertsATypeToUnhandled() {
    HandlerRegistry registry = HandlerRegistry.create(
        Map.of("application/pdf", handler(), "video/mp4", handler()),
        List.of(extension("et", context -> context.removeHandler("video/mp4"))));

    assertThat(registry.handlerFor("video/mp4")).isEmpty();
    assertThat(registry.handledMediaTypes()).containsExactly("application/pdf");
  }

  @Test
  void removeHandlerFailsFastWhenTheTypeIsNotHandled() {
    assertThatThrownBy(() -> HandlerRegistry.create(
        Map.of(),
        List.of(extension("et", context -> context.removeHandler("video/mp4")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("video/mp4");
  }

  @Test
  void extensionsApplyInOrderSoTheLastRegistrationWins() {
    DocumentHandler first = handler();
    DocumentHandler second = handler();
    HandlerRegistry registry = HandlerRegistry.create(
        Map.of("video/mp4", handler()),
        List.of(
            extension("first", context -> context.replaceHandler("video/mp4", first)),
            extension("second", context -> context.replaceHandler("video/mp4", second))));

    assertThat(registry.handlerFor("video/mp4")).contains(second);
  }

  @Test
  void mediaTypesAreNormalisedForRegistrationAndLookup() {
    DocumentHandler replacement = handler();
    HandlerRegistry registry = HandlerRegistry.create(
        Map.of("video/mp4", handler()),
        List.of(extension("et", context -> context.replaceHandler(" Video/MP4 ", replacement))));

    assertThat(registry.handlerFor("VIDEO/mp4")).contains(replacement);
  }
}
