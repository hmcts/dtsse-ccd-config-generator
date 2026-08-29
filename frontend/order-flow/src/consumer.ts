import { initAll } from "govuk-frontend";
import { type Node as ProseMirrorNode } from "prosemirror-model";

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

const controller = createOrderEditor("#editor");

function buildOrder(): ProseMirrorNode {
  const builder = createOrderBuilder();

  if (orderTextControl.checked) {
    builder.setClause("order-text", "IT IS ORDERED THAT:");
  }

  const list = builder.buildList("order-clauses")
    .listItem("li-1", "foo");

  if (secondSubparagraphControl.checked) {
    list.listItem("li-2", "bar");
  }
  if (thirdSubparagraphControl.checked) {
    list.listItem("li-3", "baz");
  }

  list.build();
  builder.setClause("outro", "that's all folks")
  return builder.build();
}

function updateStructure(): void {
  controller.render(buildOrder());
}

orderTextControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
updateStructure();
