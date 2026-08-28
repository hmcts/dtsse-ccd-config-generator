import { initAll } from "govuk-frontend";

import { createOrderBuilder } from "./builder.js";
import { createOrderEditor } from "./client.js";
import "./application.scss";

initAll();

const orderTextCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="checked"]',
);
const secondSubparagraphCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="subpara-2"]',
);
const thirdSubparagraphCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="subpara-3"]',
);

if (
  !orderTextCheckbox ||
  !secondSubparagraphCheckbox ||
  !thirdSubparagraphCheckbox
) {
  throw new Error("The structure checkboxes are missing");
}

const orderTextControl = orderTextCheckbox;
const secondSubparagraphControl = secondSubparagraphCheckbox;
const thirdSubparagraphControl = thirdSubparagraphCheckbox;

const builder = createOrderBuilder();
builder.setClause("order-text", "IT IS ORDERED THAT:");
builder.buildList("a-list")
  .listItem("li-1", "foo")
  .listItem("li-2", "bar")
  .listItem("li-3", "baz")
  .build();

const goldenDocument = builder.build();
const controller = createOrderEditor("#editor", goldenDocument);

function updateStructure(): void {
  controller.setActive("order-text", orderTextControl.checked);
  controller.setActive("li-2", secondSubparagraphControl.checked);
  controller.setActive("li-3", thirdSubparagraphControl.checked);
}

orderTextControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
updateStructure();
