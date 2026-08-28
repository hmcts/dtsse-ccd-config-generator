import { initAll } from "govuk-frontend";

import { createOrderEditor } from "./client.js";
import "./application.scss";

initAll();

const editor = createOrderEditor("#editor");
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

editor.setClause("order-text", "IT IS ORDERED THAT:");
editor.buildList("a-list")
  .listItem("li-1", "foo")
  .build()
  .build();

function updateStructure(): void {
  if (!sublistControl.checked) {
    return;
  }

}

sublistControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
updateStructure();
