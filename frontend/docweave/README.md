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
