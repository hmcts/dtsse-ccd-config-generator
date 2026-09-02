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
      import { access } from "node:fs/promises";
      import { fileURLToPath } from "node:url";
      import { buildOrder } from "@hmcts-cft/docweave";

      const document = buildOrder((order) => {
        order.paragraph("heading", "IT IS ORDERED THAT:");
      });
      if (document.node.textContent !== "IT IS ORDERED THAT:") {
        throw new Error("The installed package did not build an order");
      }

      const stylesheet = import.meta.resolve(
        "@hmcts-cft/docweave/styles/docweave.css"
      );
      await access(fileURLToPath(stylesheet));
    `,
  );
  run(process.execPath, [path.join(consumerRoot, "consumer.mjs")]);

  writeFileSync(
    path.join(consumerRoot, "consumer.ts"),
    `
      import {
        buildOrder,
        createOrderEditor,
        type DocWeaveDocument,
        type DocWeaveSnapshot,
      } from "@hmcts-cft/docweave";

      declare const mount: HTMLElement;
      const target: DocWeaveDocument = buildOrder((order) => {
        order.paragraph("heading", (content) => {
          content.fact("heading", "IT IS ORDERED THAT:", {
            sourceId: "heading-input",
          });
        });
      });
      const controller = createOrderEditor({ mount });
      controller.render(target);
      const saved: DocWeaveSnapshot = controller.getSnapshot();
      saved satisfies DocWeaveSnapshot;

      // @ts-expect-error Docweave owns its toolbar markup and behaviour.
      createOrderEditor({ mount, toolbar: mount });
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
