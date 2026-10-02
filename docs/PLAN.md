# Conduit — Delivery plan

Rules for every phase: green build, tests passing, Plugin Verifier clean on the 3 supported
majors, manual `runIde` acceptance, this file updated, tag `phase-N`. A task is checked only
after it has actually been run. `[ ]` open · `[x]` done and verified · `[~]` done, verification
pending (reason stated).

## Phase 0 — Repository foundation

- [x] Verify current versions against registries/docs → `docs/ARCHITECTURE.md`
- [x] `docs/DECISIONS.md`
- [x] Gradle 9.8 wrapper, version catalog, multi-module (`core`, `ui`, `plugin`)
- [x] Kotlin 2.4.20 at API level 2.2, JVM 21, warnings as errors
- [x] ktlint + detekt wired into `check`
- [x] Domain model in `core` (Session, Task, Plan, Step, PendingDiff, Policy, AgentEvent) with KDoc
- [x] Unit tests for the domain (state transitions, policy defaults, JSON round trip)
- [x] i18n bundles en / pt-BR / es + key-parity test
- [x] Settings → Tools → Conduit (persistent state, UI DSL) + platform test of the registration
- [x] `buildPlugin` produces `conduit-0.1.0.zip`; stdlib/coroutines not bundled
- [x] `verifyPlugin` "Compatible" with no warnings on 2025.3.6.1, 2026.1.5, 2026.2.3
- [x] CI workflow: build + tests + lint, verifier matrix (3 IDEs)
- [~] Manual acceptance: `runIde`, plugin listed, page visible under Settings → Tools → Conduit.
      Verified headlessly by `ConduitPluginTest` (page registered under Tools, panel builds and
      tracks state); the visual check in a running IDE still has to be done by a person.
- [~] CI workflow has not run yet: nothing has been pushed to GitHub.
- [x] Commit + tag `phase-0`

## Phase 1 — Agent in the chat

Spikes (results in DECISIONS):

- [x] ACP SDK vs hand-written client → hand-written (D-003)
- [x] MCP transport accepted by each CLI → HTTP for all four, stdio bridge as fallback (D-009)
- [x] Jewel/Compose tool window compiles against 253 with the bundled modules; runtime check on 261/262 by the verifier

Tasks:

- [x] `agents-api`: `AgentAdapter`, `AgentSession`, capabilities, detection, prompt, attachments, models
- [x] `agents-acp`: generic ACP client (initialize, session/new, load, prompt, updates, permission, cancel, set_mode, set_config_option, fs)
- [x] Record/replay harness in `testing/` + recordings: Claude Code (hello, edit), OpenCode (hello, edit), Gemini (startup), Codex (startup)
- [x] Adapters: Claude Code, Codex, Gemini, OpenCode (as `AcpAgentSpec`s, D-011) + custom ACP agents from settings
- [x] CLI detection (binary, version, login probe); shown in the chat's first screen and in Settings
- [x] `policy`: evaluator over `core.Policy`, modes, autonomy, shell allow/deny regex, excluded globs, per-session memory
- [x] `mcp-protocol` + `mcp-server`: loopback server, per-session token, `IdeTool` registry + extension point, pagination
- [x] MCP domain Code: `find_symbol`, `find_usages`, `go_to_definition`, `type_hierarchy`, `call_hierarchy`, `file_structure`
- [x] MCP domain Edit: `propose_edit`, `create_file`, `apply_patch` → reviewed changes
- [x] MCP refactoring: `rename_symbol` (preview from usages, real refactoring on full acceptance)
- [x] MCP domain Diagnostics: `get_problems`, `get_build_errors` (open/changed files, no build), `run_inspection`, `quick_fix` (ModCommand fixes)
- [x] MCP domain Project: `project_modules`, `dependencies`, `frameworks_detected`, `endpoints` (heuristic), `open_files`, `selection`
- [x] MCP domain Interaction: `ask_user`, `show_notification`, `open_file_at`, `request_approval`
- [x] Pending changes in the native diff viewer; per-hunk through the viewer's chunk arrows (D-013); Local History label before every apply
- [x] Chat tool window (Jewel): sessions, streaming markdown, code block actions, tool-call cards, approvals, questions, modes, models, usage
- [x] `@` mentions (file, folder, symbol, selection, file keywords) with completion; automatic editor context
- [x] Inline chat `Ctrl+I` (instruction on selection → reviewed change; D-016)
- [x] Generated commit message (commit dialog action)
- [x] Status bar widget with agent switcher; `Ctrl+Alt+A`; `Ctrl+Shift+L`; Tools → Conduit menu
- [x] **Acceptance:** "rename class X and fix usages" → Claude Code called `mcp__conduit__rename_symbol`, the change was reviewed and applied (`LiveAgentAcceptanceTest`, run live on 2026-10-02)
- [x] UI rendered off-screen by `ChatScreenRenderTest` (agent picker and a full conversation) and inspected; `verifyPlugin` Compatible on 253/261/262
- [~] Manual `runIde` walkthrough by a person: not done (no display available to the agent); button label contrast in the off-screen render should be checked in a real theme
- [x] Commit + tag `phase-1`
- [ ] Known gaps carried to Phase 2: `semantic_search` (needs the Phase 3 index), supertypes in `type_hierarchy` for JVM languages (`lang/*`), chat history persistence, images in prompts from the UI

## Phase 2 — Full copilot

- [ ] `llm-fast`: Ollama, LM Studio, llama.cpp, vLLM, OpenAI-compatible, Anthropic, Gemini, Mistral/Codestral; latency probe
- [ ] Re-verify `InlineCompletionProvider` API on 253/261/262
- [ ] `completion`: FIM ghost text, debounce, cancellation, LRU cache, PSI validation
- [ ] Partial accept (word / line), multi-line structured completion
- [ ] Next Edit Suggestion
- [ ] Latency benchmark harness (p50/p95) and accept-rate metric
- [ ] Intention actions + "Fix with AI" on inspections; implement from `TODO`
- [ ] Local branch review → findings as inspections; PR review (GitHub, GitLab, Bitbucket)
- [ ] Tests: generate, complete coverage, explain/fix failing test
- [ ] MCP domains Execution and VCS; remaining refactoring tools
- [ ] `lang/*` optional descriptors (java, kotlin, javascript, python)
- [ ] Packs Quarkus, Spring, NestJS
- [ ] **Acceptance:** autocomplete p95 < 400 ms with a local 1.5B model; branch review produces editor findings

### Phase 2 — Tool window, tasks, review and settings (delivered 2026-10-02)

- [x] Tool window with seven tabs (D-017) and a header selector for agent, model, mode or review target
- [x] Chat: timestamps, avatar from `git user.name`, plan checklist per turn, per-turn changed files measured by snapshot (D-018), apply / insert / compare (native diff) / revert, editor syntax colors in code blocks, context scope, attachments, history and resume (`session/load` without replay duplication)
- [x] Agent tab and task service: plan in read-only mode → approve/edit → one turn per step → IDE verification (D-019); pause, resume, cancel, adjust, worktree, discard; persisted across restarts
- [x] Tasks board: queue / running / review / done, parallel limit, recent runs, compare two agents (three-way diff)
- [x] Review: local changes, branch, GitHub PRs via `gh`; findings in the editor (D-020); fix, apply suggestion, create test, ignore with reason; checklist; publish after confirmation
- [x] Context tab: thirteen sources with tokens, budget, preview; secret masking; ignored and protected paths
- [x] Files tab: index-based search, sections, multi-selection, pinning, compare uncommitted changes
- [x] Actions tab: nine actions on selection/method/class/file/module/project (scopes from the Structure View), preview, slash commands; editor menu actions and "Fix with Conduit" intention
- [x] MCP domain Execution: `list_run_configs`, `run_config`, `run_tests`, `get_test_results`
- [x] MCP domain VCS: `git_status`, `git_diff`, `git_log`, `git_blame`, `git_branches` (read), `git_create_branch`, `git_commit` (asked, policy-gated); no push tool
- [x] Settings → Tools → Conduit in Compose (D-021): General, Agents, Autocomplete, Policies, Packs, Shortcuts, Privacy; framework packs Spring, Quarkus, NestJS, React, Next.js, Angular, Vue, Express, Django, FastAPI, Flask, Laravel, Android
- [x] Local audit log with detail levels (D-022)
- [x] Tests: orchestrator (tasks, plan parsing, review parsing, budget, masking, turns, history), core (file changes), ide platform tests (task lifecycle with a scripted ACP agent incl. pause/cancel and a real git worktree, context service), MCP git tools, ui off-screen renders of every screen, settings registration and persistence
- [x] Manual acceptance in a sandbox IDE (2026-10-02, IntelliJ IDEA 2025.3.6.1, dark theme): real detection of Claude Code, Codex CLI, Gemini CLI and OpenCode; a chat turn with Claude Code; an approved file creation listed, compared in the diff viewer and reverted; a real plan (5 steps, 3 risks) approved and executed step by step with approvals, paused, persisted across an IDE restart and cancelled; a real review of local changes with findings marked in the editor; settings pages opened
- [ ] Not exercised live: Codex CLI and Gemini CLI sessions (local CLI configuration problems, see the build notes), PR review and posting (no GitHub remote in the demo), worktree execution with a real agent CLI (covered by the platform test with a real git worktree and a scripted agent)

## Phase 3 — Orchestration

- [ ] Task queue, routing rules from `.conduit/config.yaml` + JSON Schema
- [ ] Project rules from `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.cursorrules`
- [ ] Compare (two agents, split view, pick winner)
- [ ] Parallel worktrees + kanban panel; declarative pipelines
- [ ] Verification loop, checkpoints and rollback, final task report
- [ ] MCP domains Debug and Database
- [ ] Semantic search (local ONNX embeddings + incremental vector index)
- [ ] Hooks, custom commands, skills, scheduled tasks
- [ ] Packs React, Django, Laravel, Android
- [ ] Remaining feature areas: git (split commits, conflicts, PR text, blame "why"), debug/diagnostics, DB/API/infra, docs, terminal
- [ ] **Acceptance:** three tasks in separate worktrees run concurrently; Claude vs Codex opens the split view

## Phase 4 — Teams and enterprise

- [ ] `backend` (Quarkus native): team license, signed central policies, opt-in anonymous telemetry
- [ ] Exportable audit log (SQLite), sensitive-data detection and masking, agent/model allowlist
- [ ] Full offline mode; Marketplace licensing (Free, Pro, Team, Enterprise)
- [ ] **Acceptance:** a central policy blocks automatic `git push` and the block shows in the audit log
