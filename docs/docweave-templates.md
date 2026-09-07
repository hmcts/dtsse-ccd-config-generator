# Docweave saved templates

The optional Docweave backend stores reusable document fragments in the PostgreSQL database owned by a decentralised
service. Every authenticated user of an authorised service can browse and use saved templates; only the user who created
a template can update or delete it.

## Add the dependency

Add the SDK module alongside the decentralised runtime:

```groovy
dependencies {
  implementation "com.github.hmcts:docweave:${ccdSdkVersion}"
}
```

The module uses the application's existing datasource, JDBC transaction manager, IDAM client, and S2S
`AuthTokenValidator`. Adding the dependency activates its Spring Boot auto-configuration; no component scan or policy bean
is required.

The shared `ccd.docweave_template` table is created by the decentralised runtime's Flyway migrations. Services receive the
empty table even when they do not include the optional Docweave module.

## Authorise calling services

Configure every S2S service that may call the endpoints:

```yaml
docweave:
  templates:
    allowed-services:
      - pcs_frontend
```

An empty or missing allowlist denies all requests. Each request must include valid `Authorization` and
`ServiceAuthorization` headers. Template ownership is derived from the authenticated user's IDAM UID and cannot be
transferred.

## HTTP API

| Method | Route | Behaviour |
|---|---|---|
| `GET` | `/docweave/templates` | Search using `query` and `scope=all\|mine` |
| `POST` | `/docweave/templates` | Create from `{title, content, tags?, searchableText?}` |
| `PUT` | `/docweave/templates/{id}` | Update from `{title, content, expectedRevision, tags?, searchableText?}` |
| `DELETE` | `/docweave/templates/{id}` | Delete using the required `expectedRevision` query parameter |

Search defaults to `scope=all`. Each whitespace-separated term must occur in the title, caller-provided search text
or tags, using case-insensitive substring matching. Short queries are supported, and wildcard characters are treated
literally. All matching templates are returned alphabetically by title, with ID breaking ties. There is no pagination:

```json
{
  "items": []
}
```

Template responses preserve the Docweave frontend fields and add tags and an ownership flag:

```json
{
  "id": "6e22c5f8-a11d-41de-8c22-e094f61e6e91",
  "title": "Directions order",
  "revision": 1,
  "updatedAt": "2026-09-07T15:25:00Z",
  "content": {
    "schema": "docweave-template",
    "version": 1,
    "content": {
      "type": "doc",
      "content": []
    }
  },
  "tags": ["directions"],
  "ownedByCurrentUser": true
}
```

Tags are stored as supplied and are searchable. Omitting `tags` when creating a template stores an empty list.
Omitting `tags` on update preserves the existing tags; passing an empty list clears them.

Updates increment `revision`. Updates and deletes require the owner and latest revision. If no row matches the ID,
owner and revision, the API returns `404` with "Template not found".

## Validation and errors

Content is stored as opaque JSON; Docweave owns its document schema and validation rules. Callers may provide
`searchableText` extracted from that content. The repository preserves supplied title and text whitespace and combines them in the indexed text column; tags
remain separate and are included in the simple text search. Basic column constraints such as the title length and
object-shaped content are enforced by PostgreSQL.

Errors use a consistent JSON shape:

```json
{
  "message": "Template not found"
}
```

The API returns `400` for invalid input, `401` for missing or invalid credentials, `403` for a denied service,
`404` when a mutation matches no template (including a different owner or stale revision), and `503` when authentication is
unavailable. Responses use `Cache-Control: private, no-store`.
