package uk.gov.hmcts.divorce.bundling;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailure;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;

/**
 * The consumer port of the document-bundling SDK: turns opaque document references into content.
 * This e2e implementation serves fixture files from the application's own classpath so the full
 * pipeline runs without any external document store; unknown ids map to a typed NOT_FOUND
 * failure exactly as a production resolver must.
 */
@Component
public class FixtureDocumentResolver implements DocumentResolver {

    public static final String PROVIDER = "case-documents";

    public static final String POTENTIAL_ENERGY_PDF = "potential-energy-pdf";
    public static final String CLAIMANT_MEDICAL_REPORT_PDF = "claimant-medical-report";
    public static final String FLYING_PIG_JPG = "flying-pig";
    public static final String WORD_DOCUMENT_DOCX = "word-document";

    private static final Map<String, Fixture> FIXTURES = Map.of(
        POTENTIAL_ENERGY_PDF, new Fixture("Potential_Energy_PDF.pdf", "application/pdf"),
        CLAIMANT_MEDICAL_REPORT_PDF, new Fixture("Claimant-Medical-Report.pdf", "application/pdf"),
        FLYING_PIG_JPG, new Fixture("flying-pig.jpg", "image/jpeg"),
        WORD_DOCUMENT_DOCX, new Fixture(
            "wordDocument2.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    );

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ResolvedDocuments resolveAll(
            final List<DocumentReference> references, final BundleExecutionContext context) {
        Map<DocumentReference, ResolvedDocument> resolved = new LinkedHashMap<>();
        Map<DocumentReference, ResolutionFailure> failures = new LinkedHashMap<>();
        for (DocumentReference reference : references) {
            Fixture fixture = FIXTURES.get(reference.id());
            if (fixture == null) {
                failures.put(reference, new ResolutionFailure(
                    ResolutionFailureReason.NOT_FOUND, "No document with this id"));
            } else {
                resolved.put(reference, new FixtureResolvedDocument(fixture));
            }
        }
        return new ResolvedDocuments(resolved, failures);
    }

    private record Fixture(String fileName, String mediaType) {

        byte[] read() {
            String resource = "/bundling-fixtures/" + fileName;
            try (InputStream in = FixtureDocumentResolver.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing bundling fixture " + resource);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read bundling fixture " + resource, e);
            }
        }
    }

    private static final class FixtureResolvedDocument implements ResolvedDocument {

        private final Fixture fixture;
        private final byte[] content;

        private FixtureResolvedDocument(Fixture fixture) {
            this.fixture = fixture;
            this.content = fixture.read();
        }

        @Override
        public InputStream content() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public String mediaType() {
            return fixture.mediaType();
        }

        @Override
        public String fileName() {
            return fixture.fileName();
        }

        @Override
        public OptionalLong contentLength() {
            return OptionalLong.of(content.length);
        }

        @Override
        public Optional<String> checksum() {
            return Optional.empty();
        }

        @Override
        public void close() {
            // Nothing to release: content is an in-memory copy of a classpath resource.
        }
    }
}
