package uk.gov.hmcts.ccd.sdk.converter.api;

import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Writes the conversion's non-source outputs: passthrough JSON (under
 * {@code options.passthroughDir}), gap-report.json and gap-report.md
 * (under {@code options.reportDir}).
 */
public interface ReportWriter {

  void write(CaseTypeModel model, GapCollector gaps, ConversionOptions options);
}
