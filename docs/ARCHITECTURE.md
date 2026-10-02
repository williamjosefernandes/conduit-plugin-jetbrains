# Conduit — Architecture

Decisions and pinned versions. Everything here was checked against the live registries and
official docs on **2026-10-02**, not taken from memory. Trade-offs and deviations from the spec
live in [DECISIONS.md](DECISIONS.md); the delivery plan in [PLAN.md](PLAN.md).

## 1. Supported IDEs

| Major  | Branch | Latest patch (verified) | Runtime Java | Bundled Kotlin stdlib |
| ------ | ------ | ----------------------- | ------------ | --------------------- |
| 2025.3 | 253    | 2025.3.6.1              | 21           | 2.2.20                |
| 2026.1 | 261    | 2026.1.5                | 21           | 2.3.20                |
| 2026.2 | 262    | 2026.2.3                | 25           | 2.4.0                 |

- **Baseline = 2025.3** (`since-build=253`): the plugin is compiled against the oldest supported
  IDE, so anything that compiles exists in all three. `until-build` is left open; forward
  compatibility is enforced by the Plugin Verifier matrix (one job per major), not by a cap.
- 2025.3 is also the first release with the unified IntelliJ IDEA distribution
  (`intellijIdea(...)` in the Gradle plugin) and the minimum for current Jewel (see §4).
- The plugin depends only on `com.intellij.modules.platform`, so it loads in every
  IntelliJ-based IDE. Language- and product-specific features go into optional descriptors
  (`lang/*`, `packs/*`) when those phases start.

## 2. Build toolchain

| Component                       | Version   | Why                                                                         |
| ------------------------------- | --------- | --------------------------------------------------------------------------- |
| Gradle (wrapper)                | 9.8.0     | Current release; IntelliJ Platform Gradle Plugin ≥ 2.12 requires Gradle 9.  |
| IntelliJ Platform Gradle Plugin | 2.19.0    | Current 2.x; supports 262 and the unified `intellijIdea` target.            |
| Kotlin compiler                 | 2.4.20    | Latest stable.                                                              |
| Kotlin language / API level     | 2.2       | Baseline IDE bundles stdlib 2.2.20; a higher API level could link against functions that do not exist at runtime in 253. |
| JVM toolchain / bytecode        | 21        | 253 and 261 run on Java 21. Do not raise to 25 while 253/261 are supported. |
| ktlint-gradle                   | 14.2.0    | Formatting gate.                                                            |
| detekt                          | 1.23.8    | Latest stable (2.0 is still alpha).                                         |
| JUnit                           | 6.1.3 (pure modules), 4.13.2 (platform tests) | `BasePlatformTestCase` is JUnit 3/4 based.          |

The Gradle plugin version is written literally in `settings.gradle.kts` (the settings `plugins`
block cannot read the version catalog); everything else is in `gradle/libs.versions.toml`.

### Libraries the IDE provides (never bundled)

Per the platform docs, plugins **must** use the IDE's `kotlinx.coroutines` (a JetBrains fork,
`1.10.x-intellij`) and must not ship the Kotlin stdlib.

- `kotlin.stdlib.default.dependency=false`; pure modules use `compileOnly(kotlin("stdlib"))`.
- `kotlinx-serialization-json` is `compileOnly` at 1.9.0 in pure modules and supplied by the IDE
  at runtime.
- Any third-party dependency added later (MCP SDK, ACP SDK, Ktor) must have its transitive
  `kotlinx-coroutines-*` and `kotlin-stdlib` excluded. `verifyPlugin` plus a packaging check
  on the built zip guard this.

## 3. Modules

Gradle multi-module, one publishable plugin. Modules are **created in the phase that first gives
them content** (see DECISIONS D-002); the target layout is the one in the product spec.

Today:

```
plugin/   main module: plugin.xml, packaging, verifier, platform tests
ui/       settings page, resource bundles (later: tool windows, inline chat, diffs)
core/     domain model — pure Kotlin, no IntelliJ dependency
```

Two kinds of module:

- **Pure** (`core`, later `agents-api`, `agents-acp`, `llm-fast`, …): `kotlin("jvm")` only.
  Fast unit tests, no IDE download, packaged as separate jars in the plugin's `lib/`.
- **Platform** (`ui`, later `mcp-server`, `completion`, `vcs`, …):
  `org.jetbrains.intellij.platform.module`, merged into the main plugin jar with
  `pluginComposedModule(...)` so the whole plugin shares one classloader and one `plugin.xml`.

Dependency direction (enforced by Gradle project dependencies — a reverse edge does not compile):

```
plugin → ui → orchestrator → agents-api → core
              mcp-server   → policy     → core
```

No domain module depends on `ui`.

## 4. UI: Jewel / Compose

Jewel and Compose are bundled with the platform since 2025.1; current Jewel requires 2025.3+.
They are consumed as **bundled modules**, not Maven artifacts:

```
intellij.platform.jewel.foundation, intellij.platform.jewel.ui,
intellij.platform.jewel.ideLafBridge, intellij.libraries.compose.foundation.desktop,
intellij.libraries.skiko
```

declared both as `bundledModule(...)` in Gradle and `<module name=…/>` in `plugin.xml`, plus the
Kotlin Compose compiler plugin. This starts in Phase 1 with the chat tool window. Settings pages
use the Kotlin UI DSL (the platform standard for settings), not Compose.

Risk to check at the start of Phase 1: the Compose runtime differs across 253/261/262 and the
Compose compiler comes from Kotlin 2.4.20. The verifier matrix covers binary compatibility; a
`runIde` smoke test per major covers runtime behavior.

## 5. Agent path: ACP

- Protocol: Agent Client Protocol, JSON-RPC over stdio. The plugin is the **client**; it spawns
  the user's own CLI as a subprocess with the user's own login. Conduit never reads CLI
  credentials and never calls provider APIs for the agent path.
- An official Kotlin SDK exists (`com.agentclientprotocol:acp` 0.30.1, pre-1.0). Conduit does
  **not** use it: `agents-acp` implements the client side of ACP v1 directly over JSON-RPC,
  reading raw JSON tolerantly (DECISIONS D-003). The SDK source is the reference for shapes.
- Launch commands, from the ACP registry (`cdn.agentclientprotocol.com/registry/v1/latest`):

  | Agent           | Kind    | Command (registry version on 2026-10-02)                      |
  | --------------- | ------- | ------------------------------------------------------------- |
  | Claude Code     | adapter | `npx @agentclientprotocol/claude-agent-acp` (0.85.1)          |
  | Codex CLI       | adapter | `npx @agentclientprotocol/codex-acp` (2.1.1)                  |
  | Gemini CLI      | native  | `gemini --acp` (0.62.0)                                       |
  | Antigravity     | native  | `agy_acp_server` binary (1.2.1)                               |
  | OpenCode        | native  | `opencode acp` (1.18.34)                                      |
  | Kiro CLI        | native  | see kiro.dev/docs/cli/acp (not in the registry; added as a custom agent) |
  | Aider           | none    | no ACP support → headless adapter (`agents-aider`)            |

  The registry is the source of truth for "any ACP-compatible agent": Phase 1 reads it rather
  than hardcoding the list, and pins nothing — the user's installed version wins.
- The IDE's capabilities reach the agent through Conduit's local MCP server, injected via the
  `mcpServers` field of the ACP session.

## 6. IDE MCP server

- `io.modelcontextprotocol:kotlin-sdk` 0.15.0 exists but is compiled against a newer Kotlin
  stdlib than the baseline IDE bundles, so `mcp-protocol` implements the tools subset of MCP
  itself (DECISIONS D-008).
- Transport: Streamable HTTP on the JDK's built-in HTTP server, bound to loopback only, random
  port, one random bearer token per agent session, non-local `Origin` refused. All four target
  agents accept HTTP MCP servers; `mcp-bridge` covers stdio-only agents (D-009).
- Every WRITE tool returns a `PendingDiff`; nothing writes to disk without going through `policy`.

## 7. Fast path: inline completion

`InlineCompletionProvider` (`com.intellij.codeInsight.inline.completion`, extension point
`com.intellij.inline.completion.provider`); the platform calls `getSuggestion` in a coroutine and
cancels it on new input; `DebouncedInlineCompletionProvider` is the debounced base class.
Completion never goes through a CLI. The API surface is re-verified against 253/261/262 at the
start of Phase 2, since it has changed between majors before.

## 8. Threading and state

- Services are `@Service` classes with an injected `CoroutineScope`; no blocking on the EDT;
  PSI reads in `readAction`, writes in `writeCommandAction`.
- Settings: `SimplePersistentStateComponent` (`conduit.xml`). Secrets go to the IDE Password
  Safe, never to state files.
- All user-visible text comes from `messages/ConduitBundle*.properties` (English default,
  Brazilian Portuguese) through `ConduitBundle`, in the language chosen in Settings → Tools →
  Conduit → General (`ConduitSettings.State.language`, stored as `en`/`pt_BR`; detected from the
  IDE or the OS until the user picks one), not the IDE's own language. A missing translation falls
  back to English; plurals use `choice` patterns. A change of language is published on
  `ConduitLanguageListener.TOPIC`; Compose text redraws by itself (each lookup reads a snapshot
  state, see `LanguageRefresh`), action texts are suppliers read each time an action is shown, and
  the settings pages are children of `ConduitConfigurable` (`dynamic="true"`) so the Settings tree
  names them in Conduit's language. Modules without the bundle (`core`, `agents-*`, `policy`,
  `orchestrator`, `mcp-server`) report failures as `ErrorCode`/`ChangeSetKind`, worded by the IDE
  layer (`ConduitErrors`). Prompts and errors meant for agents stay in English. `ConduitBundleTest`
  fails the build on missing keys, placeholder mismatches, invalid patterns or `(s)` plurals.

## 9. Domain model (`core`)

`Session`, `Task`, `Plan`/`Step`, `PendingDiff`/`Hunk`, `Policy`, `AgentEvent`. All immutable and
`@Serializable`, because the same event stream feeds the UI, the audit log and record/replay
tests. `Policy.DEFAULT_RULES` encodes the spec's default table; a partial policy falls back to
those defaults per action, so omission can never be more permissive than the default.
