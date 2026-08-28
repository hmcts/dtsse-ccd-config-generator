import { initAll } from "govuk-frontend";

import { createOrderBuilder } from "./builder.js";
import { createOrderEditor } from "./client.js";
import "./application.scss";

initAll();

const checkbox = document.querySelector<HTMLInputElement>(
  '[name="test-checkbox"]',
);

if (!checkbox) {
  throw new Error("The order checkbox is missing");
}

const orderCheckbox = checkbox;
const sublistCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="sublist"]',
);
const secondSubparagraphCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="subpara-1"]',
);
const thirdSubparagraphCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="subpara-2"]',
);

if (
  !sublistCheckbox ||
  !secondSubparagraphCheckbox ||
  !thirdSubparagraphCheckbox
) {
  throw new Error("The structure checkboxes are missing");
}

const sublistControl = sublistCheckbox;
const secondSubparagraphControl = secondSubparagraphCheckbox;
const thirdSubparagraphControl = thirdSubparagraphCheckbox;

const builder = createOrderBuilder();
builder.setClause("order-text", "IT IS ORDERED THAT:");
builder.buildList("a-list")
  .listItem("li-1", "foo")
  .build();

const goldenDocument = builder.build();
const controller = createOrderEditor("#editor", goldenDocument);

function updateStructure(): void {
  controller.setActive("a-list", sublistControl.checked);
}

sublistControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
updateStructure();
