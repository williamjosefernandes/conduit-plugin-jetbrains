# Conduit

Bring your own agent to JetBrains IDEs. Conduit runs the AI coding CLI you already have
(Claude Code, Codex CLI, Gemini CLI, OpenCode, any ACP-compatible agent) inside the IDE, with
native diffs and approvals, and exposes the IDE's semantic tools to the agent over a local MCP
server. It never resells tokens and never sends your code to a Conduit server.

Status: **Phase 1** (agent in the chat). See [docs/PLAN.md](docs/PLAN.md).

## What works today

- **Chat tool window** (right side, or Tools → Conduit → Conduit Chat): pick an installed agent,
  talk to it, watch its tool calls, approve or refuse what it wants to do, switch mode (Ask /
  Edit / Agent) and model. `@file`, `@Folder/`, `@Symbol` and `@selection` attach context.
- **IDE tools for the agent** over MCP: symbol search, usages, definitions, hierarchies, file
  structure, rename refactoring, edits and patches, problems and quick fixes, project model,
  endpoints, and asking the user. Every change the agent makes through them is reviewed first.
- **Reviews** open in the diff viewer: accept all, reject all, or apply single hunks with the
  viewer's arrows. A Local History label precedes every apply.
- `Ctrl+I` inline edit on the selection, `Ctrl+Shift+L` ask about the selection,
  `Ctrl+Alt+A` switch agent, "Generate Commit Message with Conduit" in the commit dialog.
- Policies: Ask mode is read-only, Edit mode never runs anything, shell allow/deny lists,
  secrets excluded from reads and writes, push never automatic.

## Requirements

- IntelliJ-based IDE 2025.3 or newer.
- At least one agent CLI installed and logged in: `claude`, `codex`, `gemini` or `opencode`
  (Claude Code and Codex also need `npx`, which fetches their official ACP adapter on first use).
  Conduit launches the CLI with your own account; it never stores credentials.

## Build

Requires JDK 21. The first build downloads IntelliJ IDEA 2025.3 (~1.5 GB).

| Command                                                   | What it does                                            |
| --------------------------------------------------------- | ------------------------------------------------------- |
| `./gradlew check`                                         | Unit tests, platform tests, ktlint, detekt              |
| `./gradlew buildPlugin`                                   | Plugin zip in `plugin/build/distributions/`             |
| `./gradlew runIde`                                        | Sandbox IDE with the plugin installed                   |
| `./gradlew verifyPlugin`                                  | Plugin Verifier against the 3 supported IDE majors      |
| `./gradlew :testing:record -Pagent=claude-code -Pscenario=edit` | Records a real CLI session for the replay tests   |
| `CONDUIT_LIVE_AGENT=claude-code ./gradlew :ide:test --tests '*LiveAgent*'` | Runs the live acceptance scenario |

## Docs

- [Architecture and pinned versions](docs/ARCHITECTURE.md)
- [Delivery plan](docs/PLAN.md)
- [Decisions](docs/DECISIONS.md)
