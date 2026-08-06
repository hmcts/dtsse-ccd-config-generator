package uk.gov.hmcts.example.model.common;

/**
 * A complex type whose definition ID is camelCase ({@code noticeDetails}) while the team's class is
 * PascalCase — the overwhelmingly common real shape (108 of sscs's 118 ComplexTypes IDs are
 * camelCase). {@code ModelSourceIndex.complexTypeClass} binds the two case-insensitively so the
 * members are annotated in place, but the SDK derives the EMITTED type ID from the Java simple name,
 * so without a class-level {@code @ComplexType(name = "noticeDetails", generate = true)} the type is
 * emitted as {@code NoticeDetails} — an ID the definition never mentions.
 */
public class NoticeDetails {

  private String reason;
}
