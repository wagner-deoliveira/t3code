import * as NodeServices from "@effect/platform-node/NodeServices";
import { describe, expect, it } from "@effect/vitest";
import type { OrchestratorMcpDelegateTaskInput } from "@t3tools/contracts";
import * as Effect from "effect/Effect";
import * as FileSystem from "effect/FileSystem";
import * as Path from "effect/Path";

import {
  AGENT_DEFINITIONS_DIRECTORY,
  applyAgentDefinition,
  loadAgentDefinitions,
  parseAgentDefinition,
} from "./delegatedAgents.ts";

const REVIEWER = `---
description: Reviews diffs for correctness
provider: claudeAgent
model: claude-opus-5-5
options:
  effort: high
runtimeMode: approval-required
interactionMode: plan
isolation: worktree
---

Review the change. Report bugs only.
`;

describe("parseAgentDefinition", () => {
  it("reads the frontmatter as defaults and the body as instructions", () => {
    expect(parseAgentDefinition("reviewer", REVIEWER)).toStrictEqual({
      name: "reviewer",
      description: "Reviews diffs for correctness",
      instructions: "Review the change. Report bugs only.",
      target: {
        providerInstanceId: "claudeAgent",
        model: "claude-opus-5-5",
        options: [{ id: "effort", value: "high" }],
      },
      runtimeMode: "approval-required",
      interactionMode: "plan",
      isolation: "worktree",
    });
  });

  it("accepts instructions without frontmatter and skips files it cannot use", () => {
    expect(parseAgentDefinition("planner", "Plan first.")).toMatchObject({
      instructions: "Plan first.",
      target: undefined,
    });
    expect(parseAgentDefinition("empty", "---\ndescription: none\n---\n")).toBeNull();
    expect(parseAgentDefinition("bad", "---\nruntimeMode: root\n---\nGo.")).toBeNull();
    expect(parseAgentDefinition("bad", "---\n: [\n---\nGo.")).toBeNull();
    expect(parseAgentDefinition("Not A Name", "Go.")).toBeNull();
  });
});

describe("applyAgentDefinition", () => {
  const agent = parseAgentDefinition("reviewer", REVIEWER)!;

  it("leads with the instructions and yields to what the caller passed", () => {
    const requested: OrchestratorMcpDelegateTaskInput = {
      task: "Review PR 12",
      agent: "reviewer",
      target: { providerInstanceId: "codex" as never },
      interactionMode: "default",
    };
    expect(applyAgentDefinition(requested, agent)).toStrictEqual({
      ...requested,
      task: "Review the change. Report bugs only.\n\nReview PR 12",
      runtimeMode: "approval-required",
      isolation: "worktree",
    });
  });
});

describe("loadAgentDefinitions", () => {
  it.effect("reads the project's agents by name, and none when there is no directory", () =>
    Effect.gen(function* () {
      const fileSystem = yield* FileSystem.FileSystem;
      const path = yield* Path.Path;
      const cwd = yield* fileSystem.makeTempDirectoryScoped();
      const userDirectory = path.join(cwd, "user-agents");
      expect(yield* loadAgentDefinitions(cwd, userDirectory)).toStrictEqual([]);

      const directory = path.join(cwd, AGENT_DEFINITIONS_DIRECTORY);
      yield* fileSystem.makeDirectory(directory, { recursive: true });
      yield* fileSystem.writeFileString(path.join(directory, "reviewer.md"), REVIEWER);
      yield* fileSystem.writeFileString(path.join(directory, "docs.md"), "Write docs.");
      yield* fileSystem.writeFileString(path.join(directory, "notes.txt"), "Ignored.");
      yield* fileSystem.writeFileString(path.join(directory, "broken.md"), "---\n: [\n---\nGo.");

      expect(
        (yield* loadAgentDefinitions(cwd, userDirectory)).map((agent) => agent.name),
      ).toStrictEqual(["docs", "reviewer"]);

      yield* fileSystem.makeDirectory(userDirectory, { recursive: true });
      yield* fileSystem.writeFileString(path.join(userDirectory, "reviewer.md"), "User reviewer.");
      yield* fileSystem.writeFileString(path.join(userDirectory, "planner.md"), "Plan.");
      const merged = yield* loadAgentDefinitions(cwd, userDirectory);
      expect(merged.map((agent) => agent.name)).toStrictEqual(["docs", "planner", "reviewer"]);
      expect(merged.find((agent) => agent.name === "reviewer")?.instructions).not.toBe(
        "User reviewer.",
      );
    }).pipe(Effect.provide(NodeServices.layer)),
  );
});
