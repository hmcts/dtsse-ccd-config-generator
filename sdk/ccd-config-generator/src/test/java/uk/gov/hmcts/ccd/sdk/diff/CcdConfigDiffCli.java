package uk.gov.hmcts.ccd.sdk.diff;

import java.io.File;
import java.util.Locale;

/**
 * Minimal command-line entry point that reuses {@link CcdConfigComparator} to diff CCD configuration directories.
 */
public final class CcdConfigDiffCli {

    private CcdConfigDiffCli() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java CcdConfigDiffCli <expectedDir> <actualDir>");
            System.exit(2);
        }

        File expected = new File(args[0]);
        File actual = new File(args[1]);

        if (!expected.isDirectory()) {
            System.err.printf(Locale.ROOT, "Expected directory does not exist: %s%n", expected);
            System.exit(2);
        }

        if (!actual.isDirectory()) {
            System.err.printf(Locale.ROOT, "Actual directory does not exist: %s%n", actual);
            System.exit(2);
        }

        try {
            CcdConfigComparator.compareDirectories(expected, actual);
            System.out.println("No semantic differences detected between CCD configuration outputs.");
        } catch (RuntimeException ex) {
            System.err.println("Semantic differences detected in CCD configuration outputs.");
            String message = ex.getMessage();
            if (message != null && !message.isBlank()) {
                System.err.println(message);
            }
            System.exit(1);
        }
    }
}
