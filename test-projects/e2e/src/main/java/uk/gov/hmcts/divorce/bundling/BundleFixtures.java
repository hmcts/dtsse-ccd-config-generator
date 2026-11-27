package uk.gov.hmcts.divorce.bundling;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;

/**
 * The documents the e2e bundles are built from, served from the application's classpath so the
 * suite needs no documents in CDAM beforehand. A real service passes its case's CDAM documents.
 */
public final class BundleFixtures {

    public static final String POTENTIAL_ENERGY_PDF = "Potential_Energy_PDF.pdf";
    public static final String CLAIMANT_MEDICAL_REPORT_PDF = "Claimant-Medical-Report.pdf";
    public static final String FLYING_PIG_JPG = "flying-pig.jpg";
    public static final String WORD_DOCUMENT_DOCX = "wordDocument2.docx";

    private BundleFixtures() {
    }

    public static DocSource fixture(final String fileName) {
        try (InputStream in = BundleFixtures.class.getResourceAsStream("/bundling-fixtures/" + fileName)) {
            if (in == null) {
                throw new IllegalArgumentException("No bundling fixture " + fileName);
            }
            return DocSource.bytes(in.readAllBytes(), fileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
