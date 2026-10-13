package uk.gov.hmcts.ccd.sdk.bundling.pdf;

public sealed interface AssemblyNode permits AssemblyFolder, AssemblyItem {
  String title();
}
