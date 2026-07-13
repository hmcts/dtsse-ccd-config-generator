package uk.gov.hmcts.ccd.sdk.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record CcdDataMigrationTaskOptions(
    String taskName,
    CcdDataMigrationMode mode,
    List<String> caseTypeIds,
    int eventIdWindowSize,
    int significantItemIdWindowSize,
    long caseRevisionOffset,
    int maxBatchesPerRun,
    Duration maxRunTime,
    Duration statementTimeout,
    String sourceJurisdiction
) {
  private static final String TARGET_SCHEMA = "ccd";
  private static final String FDW_SCHEMA = "fdw_stage";
  private static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofMinutes(10);

  public CcdDataMigrationTaskOptions {
    taskName = requireText(taskName, "taskName");
    mode = mode == null ? CcdDataMigrationMode.PRELOAD_EVENTS : mode;
    caseTypeIds = List.copyOf(requireCaseTypeIds(caseTypeIds));
    sourceJurisdiction = requireText(sourceJurisdiction, "sourceJurisdiction");

    if (eventIdWindowSize < 1) {
      throw new IllegalArgumentException("eventIdWindowSize must be greater than zero");
    }
    if (significantItemIdWindowSize < 1) {
      throw new IllegalArgumentException("significantItemIdWindowSize must be greater than zero");
    }
    if (caseRevisionOffset < 0) {
      throw new IllegalArgumentException("caseRevisionOffset must be zero or greater");
    }
    if (maxBatchesPerRun < 1) {
      throw new IllegalArgumentException("maxBatchesPerRun must be greater than zero");
    }
    if (maxRunTime != null && !maxRunTime.isPositive()) {
      throw new IllegalArgumentException("maxRunTime must be positive when set");
    }
    if (statementTimeout != null && !statementTimeout.isPositive()) {
      throw new IllegalArgumentException("statementTimeout must be positive when set");
    }
  }

  public static Builder builder(List<String> caseTypeIds) {
    return new Builder(caseTypeIds);
  }

  String migrationConfigHash() {
    return sha256(migrationConfigFingerprint());
  }

  String migrationConfigSummary() {
    return "targetSchema=" + TARGET_SCHEMA
        + ", fdwSchema=" + FDW_SCHEMA
        + ", sourceJurisdiction=" + sourceJurisdiction
        + ", caseTypeIds=" + canonicalCaseTypeIds()
        + ", caseRevisionOffset=" + caseRevisionOffset;
  }

  String canonicalCaseTypeIds() {
    return caseTypeIds.stream()
        .sorted()
        .collect(Collectors.joining(","));
  }

  private String migrationConfigFingerprint() {
    var fields = new ArrayList<String>();
    fields.addAll(List.of(
        "targetSchema=" + TARGET_SCHEMA,
        "fdwSchema=" + FDW_SCHEMA,
        "sourceJurisdiction=" + sourceJurisdiction,
        "caseTypeIds=" + canonicalCaseTypeIds(),
        "caseRevisionOffset=" + caseRevisionOffset
    ));
    return String.join("\n", fields);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private static List<String> requireCaseTypeIds(List<String> values) {
    Objects.requireNonNull(values, "caseTypeIds must not be null");
    if (values.isEmpty()) {
      throw new IllegalArgumentException("caseTypeIds must not be empty");
    }

    var copy = new ArrayList<String>();
    for (String value : values) {
      copy.add(requireText(value, "caseTypeIds entry"));
    }
    return copy;
  }

  public static final class Builder {
    private String taskName = "ccd-data-migration";
    private CcdDataMigrationMode mode = CcdDataMigrationMode.PRELOAD_EVENTS;
    private final List<String> caseTypeIds;
    private int eventIdWindowSize = 1_000_000;
    private int significantItemIdWindowSize = 100_000;
    private long caseRevisionOffset = 1_000_000_000L;
    private int maxBatchesPerRun = Integer.MAX_VALUE;
    private Duration maxRunTime;
    private Duration statementTimeout = DEFAULT_STATEMENT_TIMEOUT;
    private String sourceJurisdiction;

    private Builder(List<String> caseTypeIds) {
      this.caseTypeIds = caseTypeIds;
    }

    public Builder taskName(String taskName) {
      this.taskName = taskName;
      return this;
    }

    public Builder mode(CcdDataMigrationMode mode) {
      this.mode = mode;
      return this;
    }

    public Builder eventIdWindowSize(int eventIdWindowSize) {
      this.eventIdWindowSize = eventIdWindowSize;
      return this;
    }

    public Builder significantItemIdWindowSize(int significantItemIdWindowSize) {
      this.significantItemIdWindowSize = significantItemIdWindowSize;
      return this;
    }

    public Builder caseRevisionOffset(long caseRevisionOffset) {
      this.caseRevisionOffset = caseRevisionOffset;
      return this;
    }

    public Builder maxBatchesPerRun(int maxBatchesPerRun) {
      this.maxBatchesPerRun = maxBatchesPerRun;
      return this;
    }

    public Builder maxRunTime(Duration maxRunTime) {
      this.maxRunTime = maxRunTime;
      return this;
    }

    public Builder statementTimeout(Duration statementTimeout) {
      this.statementTimeout = statementTimeout;
      return this;
    }

    public Builder sourceJurisdiction(String sourceJurisdiction) {
      this.sourceJurisdiction = sourceJurisdiction;
      return this;
    }

    public CcdDataMigrationTaskOptions build() {
      return new CcdDataMigrationTaskOptions(
          taskName,
          mode,
          caseTypeIds,
          eventIdWindowSize,
          significantItemIdWindowSize,
          caseRevisionOffset,
          maxBatchesPerRun,
          maxRunTime,
          statementTimeout,
          sourceJurisdiction
      );
    }
  }
}
