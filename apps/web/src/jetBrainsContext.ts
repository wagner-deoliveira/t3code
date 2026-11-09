import { normalizeProjectPathForComparison, workspaceRelativeFilePath } from "@t3tools/shared/path";
import * as Schema from "effect/Schema";

export const JetBrainsSelection = Schema.Struct({
  projectPath: Schema.String,
  filePath: Schema.String,
  text: Schema.String,
  startLine: Schema.Int,
  endLine: Schema.Int,
  unsaved: Schema.Boolean,
});
export type JetBrainsSelection = typeof JetBrainsSelection.Type;

export function appendJetBrainsSelection(
  prompt: string,
  selection: JetBrainsSelection,
  workspaceRoot: string | undefined,
): string {
  if (
    workspaceRoot === undefined ||
    normalizeProjectPathForComparison(workspaceRoot) !==
      normalizeProjectPathForComparison(selection.projectPath)
  ) {
    throw new Error("Open a T3 thread whose workspace matches this IDE project.");
  }
  const relativePath = workspaceRelativeFilePath(selection.filePath, workspaceRoot);
  if (relativePath === null || relativePath === "." || relativePath.split(/[/\\]/).includes("..")) {
    throw new Error("The selected file is outside this thread's workspace.");
  }
  if (
    selection.startLine < 1 ||
    selection.endLine < selection.startLine ||
    selection.text.length === 0 ||
    selection.text.length > 100_000
  ) {
    throw new Error("Select between 1 and 100,000 characters from a valid line range.");
  }
  const backticks = selection.text.match(/`+/g) ?? [];
  const fence = "`".repeat(Math.max(3, ...backticks.map((run) => run.length + 1)));
  const snapshot = selection.unsaved ? "Unsaved editor snapshot" : "Editor selection";
  const context = [
    `${snapshot}: ${JSON.stringify(relativePath)}, lines ${selection.startLine}–${selection.endLine}`,
    fence,
    selection.text,
    fence,
  ].join("\n");
  return prompt.length === 0 ? context : `${prompt}\n\n${context}`;
}
