package dev.conduit.policy

import dev.conduit.core.ActionKind
import dev.conduit.core.AgentMode
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.Policy
import dev.conduit.core.PolicyDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyEngineTest {
    private fun engine(
        rules: PolicyRules = PolicyRules(),
        mode: AgentMode = AgentMode.AGENT,
        autonomy: AutonomyLevel = AutonomyLevel.ASK_EVERY_ACTION,
        inWorktree: Boolean = false,
    ) = PolicyEngine(rules, { mode }, { autonomy }, inWorktree)

    private fun PolicyEngine.outcome(
        kind: ActionKind,
        command: String? = null,
        path: String? = null,
        host: String? = null,
    ) = evaluate(ActionRequest(kind, command, path, host)).outcome

    @Test
    fun `defaults follow the documented table`() {
        val engine = engine()
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.READ_CODE, path = "src/Main.kt"))
        assertEquals(Outcome.REVIEW_DIFF, engine.outcome(ActionKind.EDIT_FILE, path = "src/Main.kt"))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.RUN_TESTS))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.SHELL_COMMAND, command = "rm -rf build"))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.NETWORK, host = "example.com"))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.GIT_COMMIT))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.GIT_PUSH))
        assertEquals(Outcome.DENY, engine.outcome(ActionKind.DB_WRITE))
    }

    @Test
    fun `secrets are excluded from reads and edits`() {
        val engine = engine()
        listOf(".env", "app/.env.local", "C:\\proj\\certs\\server.pem", "/home/u/.ssh/id_rsa", "deploy/prod.key", ".aws/credentials")
            .forEach { path ->
                assertEquals(Outcome.DENY, engine.outcome(ActionKind.READ_CODE, path = path), path)
                assertEquals(Outcome.DENY, engine.outcome(ActionKind.EDIT_FILE, path = path), path)
            }
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.READ_CODE, path = "src/environment.kt"))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.READ_CODE, path = "docs/keys.md"))
    }

    @Test
    fun `shell allowlist runs without asking and denylist always wins`() {
        val rules =
            PolicyRules(
                shellAllow = listOf("^./gradlew test", "^npm run (lint|test)$"),
                shellDeny = listOf("rm\\s+-rf", "^./gradlew publish"),
            )
        val engine = engine(rules)
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.SHELL_COMMAND, command = "./gradlew test --info"))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.SHELL_COMMAND, command = "npm run lint"))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.SHELL_COMMAND, command = "npm run build"))
        assertEquals(Outcome.DENY, engine.outcome(ActionKind.SHELL_COMMAND, command = "./gradlew test && rm -rf /"))
        assertEquals(Outcome.DENY, engine.outcome(ActionKind.SHELL_COMMAND, command = "./gradlew publish"))
    }

    @Test
    fun `ask mode is read only and edit mode never executes`() {
        val ask = engine(mode = AgentMode.ASK)
        assertEquals(Outcome.ALLOW, ask.outcome(ActionKind.READ_CODE))
        assertEquals(Outcome.DENY, ask.outcome(ActionKind.EDIT_FILE))
        assertEquals(Outcome.DENY, ask.outcome(ActionKind.SHELL_COMMAND, command = "ls"))

        val edit = engine(PolicyRules(shellAllow = listOf("^ls")), mode = AgentMode.EDIT)
        assertEquals(Outcome.REVIEW_DIFF, edit.outcome(ActionKind.EDIT_FILE))
        assertEquals(Outcome.DENY, edit.outcome(ActionKind.SHELL_COMMAND, command = "ls"))
        assertEquals(Outcome.DENY, edit.outcome(ActionKind.GIT_COMMIT))
    }

    @Test
    fun `push is never automatic`() {
        val permissive = PolicyRules(policy = Policy(mapOf(ActionKind.GIT_PUSH to PolicyDecision.ALLOW)))
        val engine = engine(permissive, autonomy = AutonomyLevel.AUTONOMOUS_WORKTREE, inWorktree = true)
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.GIT_PUSH))

        engine.remember(ActionRequest(ActionKind.GIT_PUSH))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.GIT_PUSH))

        val blocked = engine(PolicyRules(policy = Policy(mapOf(ActionKind.GIT_PUSH to PolicyDecision.DENY))))
        assertEquals(Outcome.DENY, blocked.outcome(ActionKind.GIT_PUSH))
    }

    @Test
    fun `session memory covers kinds and exact commands only`() {
        val engine = engine()
        engine.remember(ActionRequest(ActionKind.RUN_TESTS))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.RUN_TESTS))

        engine.remember(ActionRequest(ActionKind.SHELL_COMMAND, command = "ls -la"))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.SHELL_COMMAND, command = "ls -la"))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.SHELL_COMMAND, command = "ls -la /etc"))

        engine.remember(ActionRequest(ActionKind.EDIT_FILE, path = "a.kt"))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.EDIT_FILE, path = "b.kt"))
        assertEquals(Outcome.DENY, engine.outcome(ActionKind.EDIT_FILE, path = ".env"))
    }

    @Test
    fun `autonomy levels widen what runs unattended but never past deny rules`() {
        val suggest = engine(autonomy = AutonomyLevel.SUGGEST)
        assertEquals(Outcome.REVIEW_DIFF, suggest.outcome(ActionKind.EDIT_FILE))
        assertEquals(Outcome.DENY, suggest.outcome(ActionKind.RUN_TESTS))

        val allowlist = engine(autonomy = AutonomyLevel.AUTONOMOUS_ALLOWLIST)
        assertEquals(Outcome.ALLOW, allowlist.outcome(ActionKind.RUN_TESTS))
        assertEquals(Outcome.ASK, allowlist.outcome(ActionKind.SHELL_COMMAND, command = "make"))
        assertEquals(Outcome.REVIEW_DIFF, allowlist.outcome(ActionKind.EDIT_FILE))

        val worktree = engine(PolicyRules(shellDeny = listOf("^sudo")), autonomy = AutonomyLevel.AUTONOMOUS_WORKTREE, inWorktree = true)
        assertEquals(Outcome.ALLOW, worktree.outcome(ActionKind.EDIT_FILE))
        assertEquals(Outcome.ALLOW, worktree.outcome(ActionKind.SHELL_COMMAND, command = "make"))
        assertEquals(Outcome.DENY, worktree.outcome(ActionKind.SHELL_COMMAND, command = "sudo make"))
        assertEquals(Outcome.DENY, worktree.outcome(ActionKind.DB_WRITE))
        assertEquals(Outcome.ASK, worktree.outcome(ActionKind.NETWORK, host = "example.com"))

        val notIsolated = engine(autonomy = AutonomyLevel.AUTONOMOUS_WORKTREE, inWorktree = false)
        assertEquals(Outcome.REVIEW_DIFF, notIsolated.outcome(ActionKind.EDIT_FILE))
    }

    @Test
    fun `allowed hosts include their subdomains`() {
        val engine = engine(PolicyRules(allowedHosts = listOf("github.com")))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.NETWORK, host = "github.com"))
        assertEquals(Outcome.ALLOW, engine.outcome(ActionKind.NETWORK, host = "api.github.com"))
        assertEquals(Outcome.ASK, engine.outcome(ActionKind.NETWORK, host = "evilgithub.com"))
    }

    @Test
    fun `globs match names anywhere and paths from any directory`() {
        assertTrue(PathGlob("*.pem").matches("a/b/c.PEM"))
        assertFalse(PathGlob("*.pem").matches("a/b/c.pem.txt"))
        assertTrue(PathGlob(".ssh/**").matches("/home/u/.ssh/keys/id"))
        assertTrue(PathGlob("config/secrets.yml").matches("app/config/secrets.yml"))
        assertFalse(PathGlob("config/secrets.yml").matches("app/config/other/secrets.yml"))
        assertTrue(PathGlob("src/**/gen?.kt").matches("src/a/b/gen1.kt"))
        assertTrue(PathGlob("src/**/gen?.kt").matches("src/gen1.kt"))
    }
}
