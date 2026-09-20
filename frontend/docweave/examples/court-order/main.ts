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
const controller: DocEditorController = createDocEditor({
  mount,
  templates,
  onChange: (snapshot) => inspector.update(snapshot),
});

function render(): void {
  controller.render(buildDemoOrder(readInputs(form!)));
}

for (const controlGroup of controlGroups) {
  controlGroup.addEventListener("input", render);
}
form.addEventListener("reset", () => {
  // The controls hold their reset values only after the event has finished.
  window.setTimeout(() => {
    controller.load();
    render();
  }, 0);
});

render();
