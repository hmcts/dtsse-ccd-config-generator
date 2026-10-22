# Docweave

Docweave facilitates rapid generation of documents that remain user customisable and machine-readable.

Read the [interactive documentation](https://hmcts.github.io/dtsse-ccd-config-generator/docweave/):
every code block on it runs in your browser.

## Installation

```sh
npm install @hmcts-cft/docweave
```

Docweave is published as ES modules. Jest, which loads CommonJS, has to transform it:

```js
transformIgnorePatterns: ['node_modules/(?!@hmcts-cft/docweave/)'],
```

## Posting a document

Bind a uniquely named field in a form with `createDocEditor({ mount, field })`. The field
holds snapshot JSON for editing and restoring a validation page. Docweave encodes
its value as UTF-8 base64url in native submissions and `new FormData(form)`, and
removes the binding when the controller is destroyed. Bind after placing the field
in its form.

Decode the posted value on the server before validating it:

```js
const snapshot = parseSnapshot(Buffer.from(req.body.document, 'base64url').toString('utf8'));
```

This changes the submitted format for existing field bindings; update their server
handlers when upgrading. Stored snapshots and `getSnapshot()` keep their JSON format.
