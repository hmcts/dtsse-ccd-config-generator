package uk.gov.hmcts.ccd.sdk.converter.model.gap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Accumulates gap findings across the conversion pipeline. */
public class GapCollector {

  private final List<GapEntry> entries = new ArrayList<>();

  public void add(GapEntry entry) {
    entries.add(entry);
  }

  public List<GapEntry> getEntries() {
    return Collections.unmodifiableList(entries);
  }

  public boolean hasBlockingGaps() {
    return entries.stream().anyMatch(e -> e.getAction() == GapAction.OMITTED_FAIL);
  }
}
