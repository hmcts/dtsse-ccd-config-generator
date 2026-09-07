import { initAll } from "govuk-frontend";
import {
  createOrderEditor,
  type OrderEditorController,
} from "@hmcts-cft/docweave";
import "@hmcts-cft/docweave/styles/docweave.css";

import "./application.scss";
import { createInspector } from "./inspector.js";
import { readInputs } from "./inputs.js";
import { buildDemoOrder } from "./order.js";
import { createInMemoryTemplateProvider } from "./template-provider.js";

initAll();

declare global {
  interface Window {
    __DOCWEAVE_TEMPLATES_MODE__?: "standalone" | "backend";
  }
}

const form = document.querySelector<HTMLFormElement>("#order-form");
const mount = document.querySelector<HTMLElement>("#editor");
const controlGroups = document.querySelectorAll<HTMLElement>(
  "[data-order-controls]",
);
if (!form || !mount || controlGroups.length === 0) {
  throw new Error("The court-order demo is incomplete");
}

const inspector = createInspector(document);
const templates = window.__DOCWEAVE_TEMPLATES_MODE__ === "backend"
  ? { url: "/docweave/templates" }
  : { provider: createInMemoryTemplateProvider() };
const listeners = new AbortController();
let controller: OrderEditorController;

function render(): void {
  controller.render(buildDemoOrder(readInputs(form!)));
}

function initialiseEditor(): void {
  controller?.destroy();
  controller = createOrderEditor({
    mount: mount!,
    templates,
    onChange(snapshot) {
      inspector.update(snapshot);
    },
  });
  render();
}

for (const controlGroup of controlGroups) {
  controlGroup.addEventListener("input", render, {
    signal: listeners.signal,
  });
}
form.addEventListener("reset", () => {
  window.setTimeout(initialiseEditor, 0);
}, { signal: listeners.signal });

initialiseEditor();

import.meta.hot?.dispose(() => {
  listeners.abort();
  controller.destroy();
});
