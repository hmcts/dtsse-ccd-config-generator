import { initAll } from "govuk-frontend";
import {
  createOrderEditor,
  type OrderEditorController,
} from "@hmcts-cft/docweave";
import "@hmcts-cft/docweave/styles/docweave.css";

import "../court-order/application.scss";
import "./docs.scss";
import { createInMemoryTemplateProvider } from "../court-order/template-provider.js";
import { renderDocs } from "./render.js";
import {
  compileBuild,
  compileScript,
  describeError,
  type BuildFunction,
} from "./runner.js";
import { type InputValues } from "./sections.js";

const root = document.querySelector<HTMLElement>("[data-docs-root]");
if (!root) throw new Error("The documentation page is missing its root");
root.innerHTML = renderDocs();
initAll();

const CODE_DEBOUNCE_MS = 150;
const provider = createInMemoryTemplateProvider();

function required<T extends Element>(
  root: ParentNode,
  selector: string,
): T {
  const element = root.querySelector<T>(selector);
  if (!element) throw new Error(`Documentation section is missing ${selector}`);
  return element;
}

function readInputs(root: ParentNode): InputValues {
  const values: InputValues = {};
  for (const input of root.querySelectorAll<HTMLInputElement>("input[name]")) {
    values[input.name] = input.type === "checkbox" ? input.checked : input.value;
  }
  return values;
}

interface SectionElements {
  section: HTMLElement;
  code: HTMLTextAreaElement;
  mount: HTMLElement;
  output: HTMLElement;
  error: HTMLElement;
}

function sectionElements(section: HTMLElement): SectionElements {
  return {
    section,
    code: required<HTMLTextAreaElement>(section, "[data-docs-code]"),
    mount: required<HTMLElement>(section, "[data-docs-mount]"),
    output: required<HTMLElement>(section, "[data-docs-output]"),
    error: required<HTMLElement>(section, "[data-docs-error]"),
  };
}

function showError(elements: SectionElements, error: unknown): void {
  elements.error.textContent = describeError(error);
  elements.error.hidden = false;
}

function clearError(elements: SectionElements): void {
  elements.error.hidden = true;
  elements.error.textContent = "";
}

function growToContent(code: HTMLTextAreaElement): void {
  code.rows = Math.max(4, code.value.split("\n").length + 1);
}

/** A plain textarea is enough for short snippets; Tab indents rather than leaving. */
function initCodeBlock(
  elements: SectionElements,
  onChange: () => void,
): void {
  const { code, section } = elements;
  const initial = code.value;
  let pending = -1;
  growToContent(code);

  code.addEventListener("input", () => {
    growToContent(code);
    window.clearTimeout(pending);
    pending = window.setTimeout(onChange, CODE_DEBOUNCE_MS);
  });
  code.addEventListener("keydown", (event) => {
    if (event.key !== "Tab" || event.shiftKey) return;
    event.preventDefault();
    const { selectionStart, selectionEnd } = code;
    code.setRangeText("  ", selectionStart, selectionEnd, "end");
    code.dispatchEvent(new Event("input"));
  });
  section.querySelector("[data-docs-reset-code]")?.addEventListener(
    "click",
    () => {
      code.value = initial;
      growToContent(code);
      window.clearTimeout(pending);
      onChange();
    },
  );
}

function initBuildSection(section: HTMLElement): void {
  const elements = sectionElements(section);
  let build: BuildFunction | undefined;
  let controller = createOrderEditor({ mount: elements.mount });

  function render(): void {
    if (!build) return;
    try {
      const document = build(readInputs(section));
      controller.render(document);
      elements.output.textContent = document.textContent;
      clearError(elements);
    } catch (error) {
      showError(elements, error);
    }
  }

  function compile(): void {
    try {
      build = compileBuild(elements.code.value);
    } catch (error) {
      build = undefined;
      showError(elements, error);
      return;
    }
    render();
  }

  initCodeBlock(elements, compile);
  // Only the inputs panel re-renders. Typing in the editor also fires input
  // events, and re-rendering during a keystroke makes ProseMirror discard it.
  section.querySelector("[data-docs-inputs]")?.addEventListener("input", render);
  section.querySelector("[data-docs-reset-editor]")?.addEventListener(
    "click",
    () => {
      controller.destroy();
      controller = createOrderEditor({ mount: elements.mount });
      render();
    },
  );
  compile();
}

function initScriptSection(section: HTMLElement): void {
  const elements = sectionElements(section);
  let controller: OrderEditorController | undefined;

  function run(): void {
    const saved = controller?.getSnapshot();
    controller?.destroy();
    controller = undefined;
    // A script that fails after mounting leaves an editor behind; clear it so the
    // next run does not fail with "destroy it first".
    elements.mount.replaceChildren();
    elements.mount.classList.remove("docweave-editor");
    try {
      controller = compileScript(elements.code.value)({
        mount: elements.mount,
        saved,
        provider,
      });
      elements.output.textContent = JSON.stringify(
        controller.getSnapshot(),
        null,
        2,
      );
      clearError(elements);
    } catch (error) {
      elements.mount.replaceChildren();
      elements.mount.classList.remove("docweave-editor");
      showError(elements, error);
    }
  }

  initCodeBlock(elements, () => undefined);
  section.querySelector("[data-docs-run]")?.addEventListener("click", run);
  run();
}

for (const section of document.querySelectorAll<HTMLElement>("[data-docs-section]")) {
  if (section.dataset.docsKind === "script") initScriptSection(section);
  else initBuildSection(section);
}
