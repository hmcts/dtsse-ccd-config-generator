import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const consumerRoot = mkdtempSync(path.join(tmpdir(), "docweave-consumer-"));

function run(command, arguments_, workingDirectory = consumerRoot) {
  execFileSync(command, arguments_, {
    cwd: workingDirectory,
    stdio: "inherit",
  });
}

try {
  const packOutput = execFileSync(
    "npm",
    ["pack", "--json", "--pack-destination", consumerRoot],
    { cwd: projectRoot, encoding: "utf8" },
  );
  const [{ filename }] = JSON.parse(packOutput);
  const tarball = path.join(consumerRoot, filename);

  writeFileSync(
    path.join(consumerRoot, "package.json"),
    JSON.stringify({ private: true, type: "module" }),
  );
  run("npm", [
    "install",
    "--ignore-scripts",
    "--no-audit",
    "--no-fund",
    tarball,
  ]);

  writeFileSync(
    path.join(consumerRoot, "consumer.mjs"),
    `
      import assert from "node:assert/strict";
      import { access } from "node:fs/promises";
      import { createRequire } from "node:module";
      import { fileURLToPath } from "node:url";
      import {
        buildDoc,
        createDocEditor,
        TemplateRequestError,
      } from "@hmcts-cft/docweave";
      import { createTemplateProxy } from "@hmcts-cft/docweave/express";

      const require = createRequire(import.meta.url);
      const commonJs = require("@hmcts-cft/docweave");
      assert.equal(
        require("@hmcts-cft/docweave/express").createTemplateProxy,
        createTemplateProxy,
      );
      assert.ok(new TemplateRequestError("Conflict", 409) instanceof commonJs.TemplateRequestError);
      assert.ok(new commonJs.TemplateRequestError("Conflict", 409) instanceof TemplateRequestError);

      for (const [build, createEditor] of [
        [buildDoc, commonJs.createDocEditor],
        [commonJs.buildDoc, createDocEditor],
      ]) {
        const target = build((doc) => doc.paragraph("heading", "IT IS ORDERED THAT:"));
        const controller = createEditor();
        controller.render(target);
        assert.equal(controller.getDocument(), target);
        controller.destroy();
      }

      if (typeof createTemplateProxy !== "function") {
        throw new Error("The installed package did not expose its Express entry point");
      }

      const document = buildDoc((doc) => {
        doc.paragraph("heading", "IT IS ORDERED THAT:");
        doc.paragraph("costs", "Costs in the case.");
      });
      if (document.textContent !== "IT IS ORDERED THAT:\\nCosts in the case.") {
        throw new Error("The installed package did not serialize plain text with block separators");
      }
      if (document.getClause("heading")?.textContent !== "IT IS ORDERED THAT:") {
        throw new Error("The installed package did not expose its clause");
      }
      if (document.children[0] !== document.getClause("heading")) {
        throw new Error("The installed package did not preserve clause identity");
      }
      if ("node" in document) {
        throw new Error("The installed package exposed its ProseMirror node");
      }

      const stylesheet = import.meta.resolve(
        "@hmcts-cft/docweave/styles/docweave.css"
      );
      await access(fileURLToPath(stylesheet));
    `,
  );
  run(process.execPath, [path.join(consumerRoot, "consumer.mjs")]);

  writeFileSync(
    path.join(consumerRoot, "consumer.cjs"),
    `
      const { buildDoc, createDocEditor } = require("@hmcts-cft/docweave");
      const { createTemplateProxy } = require("@hmcts-cft/docweave/express");

      if (typeof createTemplateProxy !== "function") {
        throw new Error("The installed package did not expose its CommonJS Express entry point");
      }
      const document = buildDoc((doc) => {
        doc.paragraph("heading", "IT IS ORDERED THAT:");
      });
      const controller = createDocEditor();
      controller.render(document);
      if (controller.getDocument() !== document) {
        throw new Error("The CommonJS entry point did not expose the headless editor");
      }
    `,
  );
  run(process.execPath, [path.join(consumerRoot, "consumer.cjs")]);

  writeFileSync(
    path.join(consumerRoot, "consumer.ts"),
    `
      import {
        buildDoc,
        createDocEditor,
        type DocWeaveClause,
        type DocWeaveDocument,
        type DocWeaveSnapshot,
      } from "@hmcts-cft/docweave";

      declare const mount: HTMLElement;
      const target: DocWeaveDocument = buildDoc((doc) => {
        doc.paragraph("heading", (content) => {
          content.fact("heading", "IT IS ORDERED THAT:", {
            sourceId: "heading-input",
          });
        });
      });
      const heading: DocWeaveClause | undefined =
        target.getClause("heading");
      if (!heading) throw new Error("Heading clause is missing");
      heading.children satisfies readonly DocWeaveClause[];
      target.children satisfies readonly DocWeaveClause[];
      target.textContent satisfies string;
      // @ts-expect-error ProseMirror is an internal implementation detail.
      target.node;
      const controller = createDocEditor({ mount });
      controller.render(target);
      controller.getDocument() satisfies DocWeaveDocument | undefined;
      const saved: DocWeaveSnapshot = controller.getSnapshot();
      saved satisfies DocWeaveSnapshot;
      createDocEditor().render(target);

      // @ts-expect-error Docweave owns its toolbar markup and behaviour.
      createDocEditor({ mount, toolbar: mount });
    `,
  );
  writeFileSync(
    path.join(consumerRoot, "tsconfig.json"),
    JSON.stringify({
      compilerOptions: {
        module: "NodeNext",
        moduleResolution: "NodeNext",
        strict: true,
        target: "ES2023",
      },
      include: ["consumer.ts"],
    }),
  );
  const typescriptPackage = JSON.parse(
    readFileSync(
      path.join(projectRoot, "node_modules", "typescript", "package.json"),
      "utf8",
    ),
  );
  run(process.execPath, [
    path.join(projectRoot, "node_modules", "typescript", typescriptPackage.bin.tsc),
    "--project",
    path.join(consumerRoot, "tsconfig.json"),
    "--noEmit",
  ]);
} finally {
  rmSync(consumerRoot, { recursive: true, force: true });
}
