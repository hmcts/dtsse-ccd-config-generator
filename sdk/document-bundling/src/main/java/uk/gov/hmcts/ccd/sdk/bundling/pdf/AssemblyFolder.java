package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.util.List;

public record AssemblyFolder(String title, List<AssemblyNode> children) implements AssemblyNode {
  public AssemblyFolder {
    Checks.requireNonBlank(title, "AssemblyFolder.title");
    Checks.requireNonNull(children, "AssemblyFolder.children");
    children = List.copyOf(children);
  }
}
