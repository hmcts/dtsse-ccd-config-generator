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
    int eventBatchSize,
    long caseRevisionOffset,
    int maxBatchesPerRun,
    Duration maxRunTime
) {
  private static final String TARGET_SCHEMA = "ccd";
  private static final String FDW_SCHEMA = "fdw_stage";

  public CcdDataMigrationTaskOptions {
    taskName = requireText(taskName, "taskName");
    mode = mode == null ? CcdDataMigrationMode.PRELOAD_EVENTS : mode;
    caseTypeIds = List.copyOf(requireCaseTypeIds(caseTypeIds));

    if (eventBatchSize < 1) {
      throw new IllegalArgumentException("eventBatchSize must be greater than zero");
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
    private CcdDataMigrationMode mode = CcdDataMigrationMode.PRELOAD_EVENTS;
    private final List<String> caseTypeIds;
    private int eventBatchSize = 10_000;
    private long caseRevisionOffset = 1_000_000_000L;
    private int maxBatchesPerRun = Integer.MAX_VALUE;
    private Duration maxRunTime;

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

    public Builder eventBatchSize(int eventBatchSize) {
      this.eventBatchSize = eventBatchSize;
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

    public CcdDataMigrationTaskOptions build() {
      return new CcdDataMigrationTaskOptions(
          taskName,
          mode,
          caseTypeIds,
          eventBatchSize,
          caseRevisionOffset,
          maxBatchesPerRun,
          maxRunTime
      );
    }
  }
}
