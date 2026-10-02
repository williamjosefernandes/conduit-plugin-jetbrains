# Conduit

Bring your own agent to JetBrains IDEs. Conduit runs the AI coding CLI you already have
(Claude Code, Codex CLI, Gemini CLI, OpenCode, any ACP-compatible agent) inside the IDE, with
native diffs and approvals, and exposes the IDE's semantic tools to the agent over a local MCP
server. It never resells tokens and never sends your code to a Conduit server.

Status: **Phase 2** (full copilot: tool window with seven tabs, tasks, review, settings). See [docs/PLAN.md](docs/PLAN.md).

## What works today

The Conduit tool window (right side, or Tools → Conduit → Conduit Chat) has seven tabs; the
header selects the agent, model and mode (or the review target on the Review tab).

- **Chat** — streaming answers with Markdown and editor-colored code blocks, the agent's plan as a
  checklist, tool calls, approvals and questions in the conversation, and under each answer the
  files the turn changed (measured by the IDE) with *Apply changes*, *Insert in editor*, *Compare
  diffs* (native diff viewer) and *Revert all*. Context scope, file and image attachments, `@`
  mentions, cancel, history and resuming earlier conversations.
- **Agent** — a task through Task → Plan → Execution → Verification: the agent plans, you approve
  or edit the plan, each step runs as its own turn (pause, resume, cancel, adjust), optionally in
  an isolated git worktree, and the IDE verifies the result (its own analysis and the project's
  tests) before you accept or discard it.
- **Tasks** — a board (queue, running, in review, done) of tasks running in parallel, each with its
  own agent process; *Compare agents* runs one prompt with two agents and opens a three-way diff.
- **Review** — code review of local changes, the branch or a GitHub pull request (`gh`): findings
  with severity, confidence and location, also marked in the editor; fix, apply suggestion, create
  test, ignore with a reason, see in diff; checklist; post to the PR after confirmation.
- **Context** — every source that goes with a message (file, selection, problems, branch, tests,
  commits, related files, open files, structure, docs, project rules, framework packs) with a
  switch, its token cost, details, the budget and a preview. Secrets are masked; protected files
  never leave the IDE.
- **Files** — context, open, modified, related and pinned files, search over the IDE's file index,
  multi-selection, add/remove/pin, compare uncommitted changes.
- **Actions** — explain, refactor, optimize, document, add logs, generate tests, commit message,
  pull request, run command; on the selection, method, class, file, module or project, with a
  preview; also as `/` commands and in the editor's Conduit menu and Alt+Enter ("Fix with Conduit").
- **IDE tools for the agent** over MCP: symbols, usages, hierarchies, structure, rename refactoring,
  edits and patches, problems and quick fixes, run configurations and tests, project model,
  endpoints, asking the user. Every change goes through review first.
- `Ctrl+I` inline edit, ghost-text autocomplete with a fast model you choose (Ollama, LM Studio,
  llama.cpp, vLLM, OpenAI-compatible, Anthropic, Gemini, Mistral), generated commit messages.
- **Settings → Tools → Conduit**: General, Agents (real detection: binary, version, path, login),
  Autocomplete, Policies (per action kind; push is never automatic), Packs, Shortcuts (Keymap),
  Privacy. API keys live in the IDE's password safe.

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
| `./gradlew runIde -PrunIdeProject=<dir>`                  | Same, opening that project directly                     |
| `./gradlew verifyPlugin`                                  | Plugin Verifier against the 3 supported IDE majors      |
| `./gradlew :testing:record -Pagent=claude-code -Pscenario=edit` | Records a real CLI session for the replay tests   |
| `CONDUIT_LIVE_AGENT=claude-code ./gradlew :ide:test --tests '*LiveAgent*'` | Runs the live acceptance scenario |

## Docs

- [Architecture and pinned versions](docs/ARCHITECTURE.md)
- [Delivery plan](docs/PLAN.md)
- [Decisions](docs/DECISIONS.md)
