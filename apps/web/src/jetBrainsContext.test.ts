import { expect, it } from "vite-plus/test";

import { appendJetBrainsSelection, type JetBrainsSelection } from "./jetBrainsContext";

const selection: JetBrainsSelection = {
  projectPath: "C:/work/project",
  filePath: "C:/work/project/src/example.ts",
  text: "const example = `value`;",
  startLine: 2,
  endLine: 2,
  unsaved: false,
};

it("appends the editor snapshot without replacing a draft and keeps embedded fences inside it", () => {
  expect(
    appendJetBrainsSelection(
      "Explain this code",
      { ...selection, text: "```\nselected text\n```", unsaved: true },
      "c:\\work\\project\\",
    ),
  ).toBe(
    'Explain this code\n\nUnsaved editor snapshot: "src/example.ts", lines 2–2\n````\n```\nselected text\n```\n````',
  );
});

it.each([
  ["a different worktree", { projectPath: "C:/work/other" }],
  ["an outside file", { filePath: "C:/work/project-other/example.ts" }],
  ["path traversal", { filePath: "C:/work/project/../example.ts" }],
  ["an invalid line range", { startLine: 3, endLine: 2 }],
  ["an empty selection", { text: "" }],
  ["an oversized selection", { text: "x".repeat(100_001) }],
])("rejects %s before updating the composer", (_reason, change) => {
  expect(() =>
    appendJetBrainsSelection("", { ...selection, ...change }, selection.projectPath),
  ).toThrow();
});
