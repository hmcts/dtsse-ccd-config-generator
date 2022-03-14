package uk.gov.hmcts.ccd.sdk.api;

import lombok.Data;

@Data
public class SortOrder {
  public static final AscOrDesc FIRST = new AscOrDesc("1");
  public static final AscOrDesc SECOND = new AscOrDesc("2");
  public static final AscOrDesc THIRD = new AscOrDesc("3");
  public static final AscOrDesc FOURTH = new AscOrDesc("4");
  public static final AscOrDesc FIFTH = new AscOrDesc("5");

  private final String value;

  @SuppressWarnings("checkstyle:all")
  public static class AscOrDesc {
    public final SortOrder ASCENDING;
    public final SortOrder DESCENDING;

    public AscOrDesc(String order) {
      ASCENDING = new SortOrder(order + ":ASC");
      DESCENDING = new SortOrder(order + ":DESC");
    }
  }
}
