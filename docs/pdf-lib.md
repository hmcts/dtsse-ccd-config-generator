# pdf-lib (PDF generation for cftlib consumers)

`pdf-lib` is a pure-Java library that embeds the legacy `rpe-pdf-service` rendering pipeline inside this repo so CCD applications can generate PDFs in-process without calling the remote service.

## What it does

- Parses Twig/HTML templates with [Pebble](https://github.com/PebbleTemplates/pebble) (`strictVariables=true`, no caching).
- Sanitises the rendered HTML to remove characters the renderer rejects.
- Produces a PDF using [OpenHTMLtoPDF](https://github.com/danfickle/openhtmltopdf) (PDFBox backend) with the bundled `OpenSans-Regular.ttf` font embedded, so non‑ASCII characters work without extra setup.

## Public API

`PdfService` is the only public class. Typical usage:

```java
import uk.gov.hmcts.reform.cftlib.pdf.PdfService;

PdfService pdfService = PdfService.createDefault();

byte[] pdfBytes = pdfService.generate(
    """
    <html>
      <body>
        <h1>Hello {{ name }}</h1>
      </body>
    </html>
    """,
    Map.of("name", "Tribunal")
);
```

You can also provide the template as `byte[]` (useful when loading from the classpath).

## Wiring through the SDK

Most services should not depend directly on this module. Instead, flip the flag in your `build.gradle`:

```groovy
ccd {
  pdfLib = true
}
```

The CCD SDK plugin will inject `com.github.hmcts:pdf-lib:<plugin version>` into the app’s `implementation` configuration. Then expose the bean:

```java
@Configuration
class PdfServiceConfiguration {
    @Bean
    PdfService pdfService() {
        return PdfService.createDefault();
    }
}
```

and inject `PdfService` wherever you previously used the HTTP client.

## Tests

The module ships with unit tests covering sanitisation, template processing, PDF generation, and the facade. Consumers are encouraged to exercise real rendering in their own tests (no mocks needed—the library is deterministic and fast).
