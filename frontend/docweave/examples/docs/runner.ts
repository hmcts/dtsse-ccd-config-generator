import * as docweave from "@hmcts-cft/docweave";
import {
  DocWeaveDocument,
  type DocWeaveSnapshot,
  type DocEditorController,
  type TemplateProvider,
} from "@hmcts-cft/docweave";

import {
  BUILD_PARAMETERS,
  SCRIPT_PARAMETERS,
  type InputValues,
} from "./sections.js";

export type BuildFunction = (inputs: InputValues) => DocWeaveDocument;

export interface ScriptContext {
  mount: HTMLElement;
  saved: DocWeaveSnapshot | undefined;
  provider: TemplateProvider;
}

export type ScriptFunction = (
  context: ScriptContext,
) => DocEditorController;

function compile(
  parameters: readonly string[],
  code: string,
): (...values: unknown[]) => unknown {
  // The documentation runs the reader's own code in their own browser, so this
  // is no more powerful than the devtools console they already have.
  return new Function(...parameters, code) as (...values: unknown[]) => unknown;
}

/** Compiles the body of `(buildDoc, inputs) => DocWeaveDocument`. */
export function compileBuild(code: string): BuildFunction {
  const body = compile(BUILD_PARAMETERS, code);
  return (inputs) => {
    const result = body(docweave.buildDoc, inputs);
    if (!(result instanceof DocWeaveDocument)) {
      throw new TypeError(
        "The code must return the document built by buildDoc",
      );
    }
    return result;
  };
}

/** Compiles the body of `(docweave, mount, saved, provider) => controller`. */
export function compileScript(code: string): ScriptFunction {
  const body = compile(SCRIPT_PARAMETERS, code);
  return ({ mount, saved, provider }) => {
    const result = body(docweave, mount, saved, provider) as
      | Partial<DocEditorController>
      | undefined;
    if (
      typeof result?.destroy !== "function" ||
      typeof result.getSnapshot !== "function"
    ) {
      throw new TypeError(
        "The code must return the controller from createDocEditor",
      );
    }
    return result as DocEditorController;
  };
}

export function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
