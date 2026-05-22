package uk.gov.hmcts.ccd.sdk;

import java.util.List;

/**
 * Runtime hook for adding derived CCD config before the registry indexes it.
 */
@FunctionalInterface
public interface ResolvedConfigAugmenter {

  List<ResolvedCCDConfig<?, ?, ?>> augment(List<ResolvedCCDConfig<?, ?, ?>> configs);
}
