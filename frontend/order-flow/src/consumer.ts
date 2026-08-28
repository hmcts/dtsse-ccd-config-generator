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

editor.setClause("order-text", "IT IS ORDERED THAT:");

checkbox.addEventListener("change", () => {
  if (orderCheckbox.checked) {
    editor.setClause("cheese-text", "Bacon shall be had ");
  } else {
    editor.removeClause("cheese-text");
  }
});
