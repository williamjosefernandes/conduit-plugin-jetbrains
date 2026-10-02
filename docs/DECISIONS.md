# Decisions

Choices made where the spec was open, and deviations from it. Newest last.

## D-001 — Group and plugin id `dev.conduit`

The spec names the product but no id. Using group/package `dev.conduit` and plugin id
`dev.conduit`. A plugin id cannot change after the first Marketplace upload, so **confirm before
publishing** (also confirm the vendor name, currently "William Fernandes").

## D-002 — Modules are created when they get content

The spec lists ~30 modules. Phase 0 creates only `core`, `ui` and `plugin`; each other module is
added by the phase that first puts code in it. Empty modules add configuration time and noise
without enforcing anything. The target layout and dependency rules are unchanged
(ARCHITECTURE §3).

## D-003 — Hand-written ACP client instead of the official Kotlin SDK

Alternatives: (a) hand-written JSON-RPC stdio client; (b) official `com.agentclientprotocol:acp`
0.30.1. Chosen: (a), in `agents-acp`. Reasons:

1. **Baseline pinning.** The SDK is compiled against Kotlin 2.2.20 today, exactly our baseline
   stdlib. The next SDK release built with a newer Kotlin could call stdlib functions that do not
   exist in the 2025.3 IDE, and we would be stuck on an old SDK of a pre-1.0 protocol library.
2. **Tolerance.** Agents in the wild send update kinds and fields that are newer than, or outside,
   the spec. Reading raw JSON and ignoring what we do not know degrades gracefully; a typed model
   has to be kept in lockstep with every agent.
3. **Record/replay.** Contract tests replay real CLI sessions line by line; owning the wire layer
   makes that trivial.
4. **Footprint.** The SDK adds ~3.6 MB and five transitive libraries to the plugin.

The SDK source is used as the reference for message shapes, and the client is validated against
the real CLIs (recordings in `testing/`). The spec explicitly allows this path when the SDK is
immature. Revisit when the SDK reaches 1.0.

## D-004 — Compile against the oldest IDE, Kotlin API level 2.2

Compiling against 2025.3 with `apiVersion = 2.2` guarantees no use of platform or stdlib API
missing from the baseline. Cost: newer platform APIs need version-gated code; none needed yet.

## D-005 — `backend/` is a separate build, deferred to Phase 4

The Quarkus backend shares no code with the plugin and has a different toolchain. It will be its
own Gradle build inside the repo rather than a subproject of the plugin build.

## D-006 — detekt 1.23.8 with Kotlin 2.4.20

detekt 2.0 is still alpha. 1.23.8 embeds an older Kotlin compiler but analyses our sources
correctly because the language level is held at 2.2. Revisit when detekt 2.0 is stable or if it
starts misparsing.

## D-007 — First commit goes directly to `main`

The repository had no commits, so there was nothing to branch from. From Phase 1 on, work
happens on `phase-N` branches merged into `main`.

## D-008 — Hand-written MCP server instead of the Kotlin MCP SDK

`io.modelcontextprotocol:kotlin-sdk-server` 0.15.0 is compiled against Kotlin stdlib 2.4.0 and
Ktor 3.5.1. The 2025.3 IDE bundles stdlib 2.2.20, so the SDK can fail at runtime with
`NoSuchMethodError` on the baseline, and bundling a second Ktor next to the IDE's own is a
classloading risk. The IDE server only needs the tools subset of MCP over Streamable HTTP
(`initialize`, `ping`, `tools/list`, `tools/call`), which is small, so `mcp-protocol` implements
it directly on the JDK's built-in HTTP server (`jdk.httpserver`, present in the IDE runtime).
No dependencies, loopback-only, one bearer token per session. Revisit if we need resources,
prompts or server-initiated messages.

## D-009 — MCP over HTTP first, stdio bridge as fallback

All four target agents advertise `mcpCapabilities.http` (checked live on 2026-10-02), so the
IDE server is offered over HTTP. ACP only *requires* agents to support stdio MCP servers, so for
other agents `mcp-bridge` provides a dependency-free Java program that the agent starts as a
stdio MCP server and that forwards to the same HTTP endpoint. The token travels in the
environment, not on the command line.

## D-010 — Build caches stay inside the project

The Plugin Verifier's default home (`~/.pluginVerifier`) is shared by every project on the
machine; a concurrent run from another project deleted our files mid-run. `verifyPlugin` now
uses `.intellijPlatform/pluginVerifier` inside the project. Separately, on the development
machine the system disk was nearly full, so Gradle was run with
`GRADLE_USER_HOME=<project>/.gradle-home` (git-ignored). That is a local choice, not a project
setting: each IDE download is ~1.5 GB plus ~3 GB extracted, three of them for the verifier.

## D-011 — Known agents live in `agents-acp` for now

The spec lists one module per agent (`agents-claude`, `agents-codex`, …) for features beyond
ACP (cost, resume, subagents). In Phase 1 every agent is fully described by an `AcpAgentSpec`
(launch command, mode mapping, login hint), so they are data in `agents-acp/KnownAgents.kt`.
A per-agent module is created when an agent needs code of its own (D-002).

## D-012 — The agent always asks; Conduit decides

Every agent is put in its "ask before changing anything" mode, whatever the Conduit mode.
Conduit's policy engine then auto-approves, refuses or asks the user. Otherwise an agent in its
own auto-accept mode would write to disk without Conduit ever seeing the change, which would
break the rule that every write is reviewed first. Agents write to disk themselves after the
approval; the approval carries the diff, and that diff is what the user reviews.
