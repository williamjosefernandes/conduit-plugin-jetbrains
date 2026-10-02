# Conduit

Bring your own agent to JetBrains IDEs. Conduit runs the AI coding CLI you already have
(Claude Code, Codex CLI, Gemini CLI, OpenCode, any ACP-compatible agent) inside the IDE, with
native diffs and approvals, and exposes the IDE's semantic tools to the agent over a local MCP
server. It never resells tokens and never sends your code to a Conduit server.

Status: **Phase 0** (foundation). See [docs/PLAN.md](docs/PLAN.md).

## Build

Requires JDK 21. The first build downloads IntelliJ IDEA 2025.3 (~1.5 GB).

| Command                    | What it does                                             |
| -------------------------- | -------------------------------------------------------- |
| `./gradlew check`          | Unit tests, platform tests, ktlint, detekt               |
| `./gradlew buildPlugin`    | Plugin zip in `plugin/build/distributions/`              |
| `./gradlew runIde`         | Sandbox IDE with the plugin installed                    |
| `./gradlew verifyPlugin`   | Plugin Verifier against the 3 supported IDE majors       |

## Docs

- [Architecture and pinned versions](docs/ARCHITECTURE.md)
- [Delivery plan](docs/PLAN.md)
- [Decisions](docs/DECISIONS.md)
