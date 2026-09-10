import { initAll } from "govuk-frontend";
import {
  createDocEditor,
  type DocEditorController,
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
let controller: DocEditorController;

function render(): void {
  controller.render(buildDemoOrder(readInputs(form!)));
  inspector.update(controller.getSnapshot());
}

function initialiseEditor(): void {
  controller?.destroy();
  controller = createDocEditor({
    mount: mount!,
    templates,
  });
  render();
}

for (const controlGroup of controlGroups) {
  controlGroup.addEventListener("input", render);
}
form.addEventListener("reset", () => {
  window.setTimeout(initialiseEditor, 0);
});

// The inspector pulls the snapshot after each edit rather than the editor pushing it.
for (const type of ["input", "click"]) {
  mount.addEventListener(type, () => {
    inspector.update(controller.getSnapshot());
  });
}

initialiseEditor();
