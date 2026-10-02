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

Spikes first (each can change the design; results go to DECISIONS):

- [ ] ACP Kotlin SDK inside the IDE: classloading, coroutines fork, process lifecycle (D-003)
- [ ] MCP transport accepted by each CLI via ACP `mcpServers` (streamable HTTP vs SSE)
- [ ] Jewel/Compose tool window on 253, 261 and 262 with one binary

Tasks:

- [ ] `agents-api`: `AgentAdapter`, `AgentSession`, `AgentCapabilities`, `DetectionResult`, `Prompt`
- [ ] `agents-acp`: generic ACP client (initialize, session/new, prompt, updates, permission, cancel)
- [ ] Record/replay harness in `testing/` + recorded session per CLI
- [ ] Adapters: Claude Code, Codex, Gemini/Antigravity, OpenCode (launch from ACP registry)
- [ ] CLI detection (binary, version, auth check) + first-run onboarding
- [ ] `policy`: evaluator over `core.Policy`, shell allow/deny regex, excluded globs, per-session memory
- [ ] `mcp-server`: loopback server, per-session token, `IdeTool` registry, pagination
- [ ] MCP domain Code: `find_symbol`, `find_usages`, `go_to_definition`, `type_hierarchy`, `call_hierarchy`, `file_structure`
- [ ] MCP domain Edit: `propose_edit`, `create_file`, `apply_patch` → `PendingDiff`
- [ ] MCP refactoring needed for acceptance: `rename_symbol` → `PendingDiff`
- [ ] MCP domain Diagnostics: `get_problems`, `run_inspection`, `get_build_errors`, `quick_fix`
- [ ] MCP domain Project: `project_modules`, `dependencies`, `frameworks_detected`, `endpoints`, `open_files`, `selection`
- [ ] MCP domain Interaction: `ask_user`, `show_notification`, `open_file_at`, `request_approval`
- [ ] Pending diffs in the native diff viewer, accept/reject per hunk, shortcuts
- [ ] Chat tool window (Jewel): tabs, streaming, tool-call cards, approvals, modes Ask/Edit/Agent
- [ ] `@` mentions (file, class, method, folder first) with completion; automatic context
- [ ] Inline chat `Ctrl+I`
- [ ] Generated commit message
- [ ] Status bar widget, agent switcher `Ctrl+Alt+A`
- [ ] **Acceptance:** "rename class X and fix usages" → agent calls `rename_symbol` → user approves the diff in the IDE

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
