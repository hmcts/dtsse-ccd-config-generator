package uk.gov.hmcts.ccd.sdk.doctoolkit.pdf;

public sealed interface AssemblyNode permits AssemblyFolder, AssemblyItem {
  String title();
}
