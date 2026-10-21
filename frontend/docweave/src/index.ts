export {
  buildDoc,
  type DocWeaveClause,
  DocWeaveDocument,
  type FactOptions,
  type InlineBuilder,
  type ListItemBuilder,
  type DocBuilder,
  type OrderedListBuilder,
} from "./builder.js";
export {
  createDocEditor,
  type CreateDocEditorOptions,
} from "./client.js";
export {
  type DocEditorController,
  type InsertHtmlOptions,
  type InsertOptions,
} from "./controller.js";
export {
  type DocWeaveSnapshot,
  InvalidSnapshotError,
  type InvalidSnapshotCode,
  parseSnapshot,
} from "./snapshot.js";
export { describeChanges, type DocumentChanges } from "./changes.js";
export { renderHtml, type RenderHtmlOptions, renderText } from "./html.js";
export {
  createHttpTemplateProvider,
  createTemplateFragment,
  parseTemplateFragment,
  TemplateRequestError,
  type HttpTemplateProviderOptions,
  type SaveTemplateInput,
  type Template,
  type TemplateFragment,
  type TemplateProvider,
  type TemplateSearchResult,
} from "./templates/index.js";
