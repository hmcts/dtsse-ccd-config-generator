package uk.gov.hmcts.ccd.sdk.retention;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CaseRetentionService {
  private static final int CCD_EXISTENCE_BATCH_LIMIT = 100;

  private final RetentionCaseDataRepository repository;
  private final CcdCaseDataExistenceClient ccdCaseDataExistenceClient;

  public CaseRetentionService(RetentionCaseDataRepository repository,
                              CcdCaseDataExistenceClient ccdCaseDataExistenceClient) {
    this.repository = repository;
    this.ccdCaseDataExistenceClient = ccdCaseDataExistenceClient;
  }

  public RetentionTaskResult run(Collection<String> deletionCaseTypeIds,
                                 Collection<String> simulationCaseTypeIds,
                                 int batchSize) {
    ModeResult deletionResult = processMode(deletionCaseTypeIds, batchSize, false);
    ModeResult simulationResult = processMode(simulationCaseTypeIds, batchSize, true);
    return new RetentionTaskResult(
        deletionResult.affectedCases(),
        simulationResult.affectedCases(),
        deletionResult.skippedCases() + simulationResult.skippedCases()
    );
  }

  private ModeResult processMode(Collection<String> caseTypeIds, int batchSize, boolean simulation) {
    if (caseTypeIds.isEmpty()) {
      log.info("Case retention {} mode has no configured case types", modeName(simulation));
      return new ModeResult(0, 0);
    }
    if (batchSize <= 0) {
      log.info("Case retention {} mode has non-positive batch size ({}); skipping", modeName(simulation), batchSize);
      return new ModeResult(0, 0);
    }

    List<RetentionCaseData> candidates = repository.findExpiredCases(caseTypeIds, batchSize);
    log.info("Case retention {} mode found {} candidate cases", modeName(simulation), candidates.size());

    if (simulation) {
      return new ModeResult(candidates.size(), 0);
    }

    int deletedCases = 0;
    int skippedCases = 0;
    Map<String, List<RetentionCaseData>> candidatesByJurisdiction = candidates.stream()
        .collect(Collectors.groupingBy(RetentionCaseData::jurisdiction));

    for (Map.Entry<String, List<RetentionCaseData>> jurisdictionCandidates : candidatesByJurisdiction.entrySet()) {
      for (List<RetentionCaseData> batch : partition(jurisdictionCandidates.getValue())) {
        ExistenceCheckResult checkResult = checkCcdPointers(jurisdictionCandidates.getKey(), batch);
        skippedCases += checkResult.skippedCases();
        if (!checkResult.deletableReferences().isEmpty()) {
          deletedCases += repository.deleteCases(checkResult.deletableReferences());
        }
      }
    }

    log.info("Case retention {} mode deleted {} cases and skipped {} cases",
        modeName(simulation), deletedCases, skippedCases);
    return new ModeResult(deletedCases, skippedCases);
  }

  private ExistenceCheckResult checkCcdPointers(String jurisdiction, List<RetentionCaseData> batch) {
    List<Long> references = batch.stream()
        .map(RetentionCaseData::reference)
        .distinct()
        .toList();
    try {
      Map<Long, Boolean> existenceResults = ccdCaseDataExistenceClient.caseDataExists(jurisdiction, references);
      List<Long> deletableReferences = new ArrayList<>();
      int skippedCases = 0;

      for (Long reference : references) {
        Boolean exists = existenceResults.get(reference);
        if (Boolean.FALSE.equals(exists)) {
          deletableReferences.add(reference);
        } else {
          skippedCases++;
          if (exists == null) {
            log.warn("CCD existence check omitted case {}; skipping local deletion", reference);
          }
        }
      }

      return new ExistenceCheckResult(deletableReferences, skippedCases);
    } catch (RuntimeException exception) {
      log.error("CCD existence check failed for jurisdiction {}; skipping {} local cases",
          jurisdiction, references.size(), exception);
      return new ExistenceCheckResult(List.of(), references.size());
    }
  }

  private List<List<RetentionCaseData>> partition(List<RetentionCaseData> candidates) {
    Set<Long> seenReferences = new LinkedHashSet<>();
    List<RetentionCaseData> distinctCandidates = candidates.stream()
        .filter(candidate -> seenReferences.add(candidate.reference()))
        .toList();
    List<List<RetentionCaseData>> partitions = new ArrayList<>();
    for (int start = 0; start < distinctCandidates.size(); start += CCD_EXISTENCE_BATCH_LIMIT) {
      partitions.add(distinctCandidates.subList(
          start,
          Math.min(start + CCD_EXISTENCE_BATCH_LIMIT, distinctCandidates.size())
      ));
    }
    return partitions;
  }

  private String modeName(boolean simulation) {
    return simulation ? "simulation" : "deletion";
  }

  private record ModeResult(int affectedCases, int skippedCases) {
  }

  private record ExistenceCheckResult(Collection<Long> deletableReferences, int skippedCases) {
  }
}
