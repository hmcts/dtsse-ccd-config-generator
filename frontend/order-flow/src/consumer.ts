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

function updateStructure(): void {
  if (!sublistControl.checked) {
    editor.removeClause("a-list");
    return;
  }

  const list = editor.buildList("a-list")
    .listItem("para-1", "dawg");

  if (secondSubparagraphControl.checked) {
    list.listItem("para-2", "bruh");
  }

  if (thirdSubparagraphControl.checked) {
    list.listItem("para-3", "Third subparagraph");
  }

  list.build();
}

sublistControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
updateStructure();

checkbox.addEventListener("change", () => {
  if (orderCheckbox.checked) {
    editor.setClause("cheese-text", "Bacon shall be had by ");
  } else {
    editor.removeClause("cheese-text");
  }
});
