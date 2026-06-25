package uk.gov.hmcts.ccd.sdk.migration;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record CcdDataMigrationTaskOptions(
    String taskName,
    String targetSchema,
    String fdwSchema,
    List<String> caseTypeIds,
    int batchSize,
    long caseRevisionOffset,
    int maxBatchesPerRun,
    Duration maxRunTime,
    LocalDateTime runUntil,
    Duration deltaOverlap
) {
  private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  public CcdDataMigrationTaskOptions {
    taskName = requireText(taskName, "taskName");
    targetSchema = requireIdentifier(targetSchema, "targetSchema");
    fdwSchema = requireIdentifier(fdwSchema, "fdwSchema");
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
  }

  public static Builder builder(List<String> caseTypeIds) {
    return new Builder(caseTypeIds);
  }

  static String requireIdentifier(String value, String fieldName) {
    var text = requireText(value, fieldName);
    if (!SQL_IDENTIFIER.matcher(text).matches()) {
      throw new IllegalArgumentException(fieldName + " must be a simple SQL identifier");
    }
    return text;
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
    private String targetSchema = "ccd";
    private String fdwSchema = "fdw_stage";
    private final List<String> caseTypeIds;
    private int batchSize = 100;
    private long caseRevisionOffset = 1_000_000_000L;
    private int maxBatchesPerRun = Integer.MAX_VALUE;
    private Duration maxRunTime;
    private LocalDateTime runUntil;
    private Duration deltaOverlap = Duration.ofMinutes(15);

    private Builder(List<String> caseTypeIds) {
      this.caseTypeIds = caseTypeIds;
    }

    public Builder taskName(String taskName) {
      this.taskName = taskName;
      return this;
    }

    public Builder targetSchema(String targetSchema) {
      this.targetSchema = targetSchema;
      return this;
    }

    public Builder fdwSchema(String fdwSchema) {
      this.fdwSchema = fdwSchema;
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

    public CcdDataMigrationTaskOptions build() {
      return new CcdDataMigrationTaskOptions(
          taskName,
          targetSchema,
          fdwSchema,
          caseTypeIds,
          batchSize,
          caseRevisionOffset,
          maxBatchesPerRun,
          maxRunTime,
          runUntil,
          deltaOverlap
      );
    }
  }
}
