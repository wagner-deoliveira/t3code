# Agent definitions

Define named agents for a project, such as a planner, an implementer, and a
reviewer. A thread's agent can then hand work to one by name, and it runs as its
own thread with the model, instructions, and limits you set.

## Define an agent

Add a Markdown file to `.agents/agents/` in the project. The file name, without
`.md`, is the agent's name: lowercase letters, numbers, and hyphens. The text
below the frontmatter is the agent's instructions.

```markdown
---
description: Reviews diffs for correctness bugs
provider: claudeAgent
model: claude-opus-5-5
options:
  effort: high
runtimeMode: approval-required
interactionMode: plan
isolation: worktree
---

Review the change you are given. Report bugs only, each with the file and line.
```

Every field in the frontmatter is optional:

| Field             | Meaning                                                                         |
| ----------------- | ------------------------------------------------------------------------------- |
| `description`     | What the agent is for. The delegating agent reads it to choose.                 |
| `provider`        | The provider instance to run on, as listed in **Settings > Providers**.         |
| `model`           | The model to use. Without `provider`, it applies to the delegating thread's.    |
| `options`         | Model options such as `effort`, as the model picker lists them.                 |
| `runtimeMode`     | `approval-required`, `auto-accept-edits`, `auto`, or `full-access`.             |
| `interactionMode` | `default` or `plan`.                                                            |
| `isolation`       | `shared` (the default) runs in the same checkout. `worktree` gets its own copy. |

An agent that leaves out `provider` and `model` runs on whatever the delegating
thread uses.

## Agents for every project

Put the same kind of file in the `agents` folder of your T3 home (`~/.t3/agents/`
by default) and every project can use it. When a project defines an agent with the
same name, the project's wins.

## Use an agent

Ask the thread's agent to use one, for example "have the reviewer check this
branch". The agent sees the project's definitions and delegates with the agent's
name. Instructions lead the task, and anything the delegating agent sets for the
task itself wins over the agent's defaults.

An agent can never run broader than the thread that delegates to it. If the
definition asks for `full-access` and the thread is supervised, the delegation is
refused.

Definitions are read from the delegating thread's checkout and your T3 home each time, so edits
apply to the next delegation. A file that does not parse is skipped. Agents run in
this environment only, not in a linked one.

## Your OpenCode agents

OpenCode agents are separate: pick them in the model options or cycle them with
**Composer: Cycle Agent**. See [OpenCode](./providers-opencode.md#agents).
