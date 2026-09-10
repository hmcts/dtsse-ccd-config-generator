import {
  BUILD_PARAMETERS,
  SCRIPT_PARAMETERS,
  inputControlId,
  sections,
  templatesInApplicationExample,
  type DocsSection,
  type SectionInput,
} from "./sections.js";

const ARCHITECTURE_URL =
  "https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/frontend/docweave/architecture.md";

export function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function button(
  text: string,
  attribute: string,
  secondary = false,
): string {
  const classes = secondary ? " govuk-button--secondary" : "";
  return `<button type="button" class="govuk-button${classes}" data-module="govuk-button" ${attribute}>${text}</button>`;
}

function renderInput(sectionId: string, input: SectionInput): string {
  const id = inputControlId(sectionId, input.name);
  if (input.kind === "checkbox") {
    return `<div class="govuk-form-group">
  <div class="govuk-checkboxes govuk-checkboxes--small" data-module="govuk-checkboxes">
    <div class="govuk-checkboxes__item">
      <input class="govuk-checkboxes__input" id="${id}" name="${escapeHtml(input.name)}" type="checkbox" value="yes"${input.checked ? " checked" : ""}>
      <label class="govuk-label govuk-checkboxes__label" for="${id}">${escapeHtml(input.label)}</label>
    </div>
  </div>
</div>`;
  }
  return `<div class="govuk-form-group">
  <label class="govuk-label" for="${id}">${escapeHtml(input.label)}</label>
  <input class="govuk-input" id="${id}" name="${escapeHtml(input.name)}" type="text" value="${escapeHtml(input.value)}">
</div>`;
}

function preamble(section: DocsSection): string {
  return section.kind === "script"
    ? `// (${SCRIPT_PARAMETERS.join(", ")}) => controller`
    : `// (${BUILD_PARAMETERS.join(", ")}) => DocWeaveDocument`;
}

export function renderSection(section: DocsSection): string {
  const script = section.kind === "script";
  const inputs = section.inputs.length === 0 ? "" : `
<div class="docs-inputs" data-docs-inputs>
  <h3 class="govuk-heading-s">Inputs</h3>
  ${section.inputs.map((input) => renderInput(section.id, input)).join("\n")}
</div>`;
  const application = section.id === "templates" ? `
<h3 class="govuk-heading-m">In an application</h3>
<pre class="docs-code__static"><code>${escapeHtml(templatesInApplicationExample)}</code></pre>` : "";

  return `<section class="docs-section" id="${section.id}" aria-labelledby="${section.id}-heading" data-docs-section="${section.id}" data-docs-kind="${section.kind}">
  <h2 class="govuk-heading-l" id="${section.id}-heading">${escapeHtml(section.title)}</h2>
  <div class="docs-section__prose">
    ${section.prose.map((paragraph) => `<p class="govuk-body">${escapeHtml(paragraph)}</p>`).join("\n    ")}
  </div>
  <div class="docs-layout">
    <div class="docs-code">
      <label class="docs-code__label" for="${section.id}-code">${script ? "Code, run when you press Run" : "Code, run as you type"}</label>
      <pre class="docs-code__preamble" aria-hidden="true">${escapeHtml(preamble(section))}</pre>
      <textarea class="docs-code__editor" id="${section.id}-code" spellcheck="false" autocapitalize="off" autocomplete="off" data-docs-code>${escapeHtml(section.code)}</textarea>
      <div class="docs-code__actions">
        ${script ? button("Run", "data-docs-run") : button("Reset editor", "data-docs-reset-editor", true)}
        ${button("Reset code", "data-docs-reset-code", true)}
      </div>
      <div class="docs-error" role="alert" hidden data-docs-error></div>${inputs}
    </div>
    <div class="docs-editor">
      <h3 class="govuk-heading-s">Editor</h3>
      <div class="docs-editor__surface" data-docs-mount></div>
      <div class="docs-output">
        <h3 class="govuk-heading-s">${script ? "Snapshot after Run" : "document.textContent"}</h3>
        <pre data-docs-output></pre>
      </div>
    </div>
  </div>
  <div class="docs-try">
    <h3 class="govuk-heading-s">Try this</h3>
    <ol class="govuk-list govuk-list--number">
      ${section.tryThis.map((step) => `<li>${escapeHtml(step)}</li>`).join("\n      ")}
    </ol>
  </div>${application}
</section>`;
}

export function renderDocs(): string {
  return `<div class="docs-introduction">
  <h1 class="govuk-heading-xl">Docweave</h1>
  <p class="govuk-body-l">Docweave generates documents from code that stay editable by people and readable by machines. Every code block on this page runs in your browser. Edit it and watch the editor beside it.</p>
  <p class="govuk-body">Install it with <code>npm install @hmcts-cft/docweave</code> and import <code>@hmcts-cft/docweave/styles/docweave.css</code> for the editor styles.</p>
</div>
<nav class="docs-contents" aria-label="Contents">
  <h2 class="govuk-heading-s">Contents</h2>
  <ol class="govuk-list govuk-list--number">
    ${sections.map((section) => `<li><a class="govuk-link" href="#${section.id}">${escapeHtml(section.title)}</a></li>`).join("\n    ")}
  </ol>
</nav>
${sections.map(renderSection).join("\n")}
<p class="govuk-body">The reconciliation rules and the invariants generated documents must obey are described in <a class="govuk-link" href="${ARCHITECTURE_URL}">architecture.md</a>. The <a class="govuk-link" href="./playground/">court order playground</a> is a fuller example.</p>`;
}
