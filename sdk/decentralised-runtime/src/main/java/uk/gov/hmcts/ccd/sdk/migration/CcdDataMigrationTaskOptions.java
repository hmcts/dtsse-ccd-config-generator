package uk.gov.hmcts.ccd.sdk.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record CcdDataMigrationTaskOptions(
    String taskName,
    List<String> caseTypeIds,
    int batchSize,
    long caseRevisionOffset,
    int maxBatchesPerRun,
    Duration maxRunTime,
    LocalDateTime runUntil,
    Duration deltaOverlap,
    CcdDataMigrationValidationMode validationMode
) {
  private static final String TARGET_SCHEMA = "ccd";
  private static final String FDW_SCHEMA = "fdw_stage";

  public CcdDataMigrationTaskOptions {
    taskName = requireText(taskName, "taskName");
    caseTypeIds = List.copyOf(requireCaseTypeIds(caseTypeIds));

    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be greater than zero");
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
    if (deltaOverlap == null) {
      deltaOverlap = Duration.ofMinutes(15);
    }
    if (deltaOverlap.isNegative()) {
      throw new IllegalArgumentException("deltaOverlap must be zero or greater");
    }
    if (validationMode == null) {
      validationMode = CcdDataMigrationValidationMode.DELTA_ONLY;
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
        + ", caseTypeIds=" + canonicalCaseTypeIds()
        + ", caseRevisionOffset=" + caseRevisionOffset;
  }

  String canonicalCaseTypeIds() {
    return caseTypeIds.stream()
        .sorted()
        .collect(Collectors.joining(","));
  }

  private String migrationConfigFingerprint() {
    return String.join(
        "\n",
        "targetSchema=" + TARGET_SCHEMA,
        "fdwSchema=" + FDW_SCHEMA,
        "caseTypeIds=" + canonicalCaseTypeIds(),
        "caseRevisionOffset=" + caseRevisionOffset
    );
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
    private final List<String> caseTypeIds;
    private int batchSize = 100;
    private long caseRevisionOffset = 1_000_000_000L;
    private int maxBatchesPerRun = Integer.MAX_VALUE;
    private Duration maxRunTime;
    private LocalDateTime runUntil;
    private Duration deltaOverlap = Duration.ofMinutes(15);
    private CcdDataMigrationValidationMode validationMode = CcdDataMigrationValidationMode.DELTA_ONLY;

    private Builder(List<String> caseTypeIds) {
      this.caseTypeIds = caseTypeIds;
    }

    public Builder taskName(String taskName) {
      this.taskName = taskName;
      return this;
    }

    public Builder batchSize(int batchSize) {
      this.batchSize = batchSize;
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

    public Builder runUntil(LocalDateTime runUntil) {
      this.runUntil = runUntil;
      return this;
    }

    public Builder deltaOverlap(Duration deltaOverlap) {
      this.deltaOverlap = deltaOverlap;
      return this;
    }

    public Builder validationMode(CcdDataMigrationValidationMode validationMode) {
      this.validationMode = validationMode;
      return this;
    }

    public CcdDataMigrationTaskOptions build() {
      return new CcdDataMigrationTaskOptions(
          taskName,
          caseTypeIds,
          batchSize,
          caseRevisionOffset,
          maxBatchesPerRun,
          maxRunTime,
          runUntil,
          deltaOverlap,
          validationMode
      );
    }
  }
}
