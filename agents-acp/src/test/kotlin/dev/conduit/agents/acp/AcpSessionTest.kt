package dev.conduit.agents.acp

import dev.conduit.agents.AgentAuthRequiredException
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.Attachment
import dev.conduit.agents.ClientFileSystem
import dev.conduit.agents.McpServerConfig
import dev.conduit.agents.Prompt
import dev.conduit.agents.SessionConfig
import dev.conduit.agents.StdioCommand
import dev.conduit.agents.acp.rpc.JsonRpcException
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.core.StepStatus
import dev.conduit.core.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pipe = InMemoryPipe()

    private val spec =
        KnownAgents.CLAUDE_CODE.copy(
            modeIds = mapOf(AgentMode.ASK to listOf("plan"), AgentMode.AGENT to listOf("default")),
        )

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val initResult =
        json(
            """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,
            "promptCapabilities":{"image":true,"embeddedContext":true},"mcpCapabilities":{"http":true}}}""",
        )

    private val sessionResult =
        json(
            """{"sessionId":"s-1","modes":{"currentModeId":"default",
            "availableModes":[{"id":"default","name":"Manual"},{"id":"plan","name":"Plan"}]}}""",
        )

    /** An agent that completes the handshake and delegates everything else to [turn]. */
    private fun agent(
        init: JsonObject = initResult,
        turn: suspend FakeAgent.(method: String, params: JsonObject) -> JsonElement = { _, _ -> JsonObject(emptyMap()) },
    ) = FakeAgent(pipe.agent, scope) { method, params ->
        when (method) {
            "initialize" -> init
            "session/new", "session/load" -> sessionResult
            else -> turn(method, params)
        }
    }

    private suspend fun open(config: SessionConfig = SessionConfig(cwd = "/work")): AcpSession =
        AcpSession(spec, config, pipe.client, CoroutineScope(scope.coroutineContext + SupervisorJob())).also { it.open() }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    /** Collects the events of one turn, up to and including its end. */
    private suspend fun AcpSession.turn(): List<AgentEvent> {
        val seen = ArrayList<AgentEvent>()
        events
            .takeWhile { event ->
                seen += event
                event !is AgentEvent.Done && event !is AgentEvent.Error
            }.toList()
        return seen
    }

    @Test
    fun `handshake negotiates and exposes the session id`() =
        test {
            val agent = agent()
            val session = open()

            assertEquals("s-1", session.id.value)
            assertEquals("s-1", session.resumeToken)
            val init = agent.requested("initialize").single()
            assertEquals(1, init["protocolVersion"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                "false",
                init["clientCapabilities"]!!
                    .jsonObject["fs"]!!
                    .jsonObject["writeTextFile"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "/work",
                agent
                    .requested("session/new")
                    .single()["cwd"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `mcp servers are offered over http when the agent supports it and over stdio otherwise`() =
        test {
            val server = McpServerConfig("conduit", "http://127.0.0.1:1/mcp", mapOf("Authorization" to "Bearer t"), StdioCommand("java"))
            val agent = agent()
            open(SessionConfig(cwd = "/work", mcpServers = listOf(server)))
            val http =
                agent
                    .requested("session/new")
                    .single()["mcpServers"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("http", http["type"]!!.jsonPrimitive.content)
            assertEquals(
                "Authorization",
                http["headers"]!!
                    .jsonArray
                    .single()
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `stdio fallback is used when the agent has no http mcp support`() =
        test {
            val server = McpServerConfig("conduit", "http://127.0.0.1:1/mcp", stdioFallback = StdioCommand("java", listOf("-jar", "b.jar")))
            val agent = agent(init = json("""{"protocolVersion":1}"""))
            open(SessionConfig(cwd = "/work", mcpServers = listOf(server)))
            val stdio =
                agent
                    .requested("session/new")
                    .single()["mcpServers"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("java", stdio["command"]!!.jsonPrimitive.content)
            assertNull(stdio["type"])
        }

    @Test
    fun `resume token loads the existing session`() =
        test {
            val agent = agent()
            open(SessionConfig(cwd = "/work", resumeToken = "s-0"))
            assertEquals(
                "s-0",
                agent
                    .requested("session/load")
                    .single()["sessionId"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(agent.requested("session/new").isEmpty())
        }

    @Test
    fun `initial mode is applied through set_mode`() =
        test {
            val agent = agent()
            open(SessionConfig(cwd = "/work", mode = AgentMode.ASK))
            assertEquals(
                "plan",
                agent
                    .requested("session/set_mode")
                    .single()["modeId"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `modes exposed as a config option are set through set_config_option`() =
        test {
            val result =
                json(
                    """{"sessionId":"s-1","configOptions":[{"id":"mode","category":"mode","currentValue":"build",
                    "options":[{"value":"build"},{"value":"plan"}]}]}""",
                )
            val agent =
                FakeAgent(pipe.agent, scope) { method, _ ->
                    when (method) {
                        "initialize" -> initResult
                        "session/new" -> result
                        else -> JsonObject(emptyMap())
                    }
                }
            open(SessionConfig(cwd = "/work", mode = AgentMode.ASK))
            val request = agent.requested("session/set_config_option").single()
            assertEquals("mode", request["configId"]!!.jsonPrimitive.content)
            assertEquals("plan", request["value"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a turn streams text thinking tools plan and usage then ends`() =
        test {
            agent { method, _ ->
                if (method == "session/prompt") {
                    update("s-1", json("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hm"}}"""))
                    update("s-1", json("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}"""))
                    update("s-1", json("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}"""))
                    update(
                        "s-1",
                        json(
                            """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Read A.kt","kind":"read",
                            "status":"pending","rawInput":{"path":"A.kt"}}""",
                        ),
                    )
                    update(
                        "s-1",
                        json(
                            """{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed",
                            "content":[{"type":"content","content":{"type":"text","text":"class A"}}]}""",
                        ),
                    )
                    update(
                        "s-1",
                        json(
                            """{"sessionUpdate":"plan","entries":[{"content":"one","priority":"high","status":"completed"},
                            {"content":"two","priority":"low","status":"in_progress"}]}""",
                        ),
                    )
                    update("s-1", json("""{"sessionUpdate":"usage_update","used":10,"size":100,"cost":{"amount":0.5,"currency":"USD"}}"""))
                    update("s-1", json("""{"sessionUpdate":"something_new","x":1}"""))
                    json("""{"stopReason":"end_turn","usage":{"inputTokens":7,"outputTokens":3,"totalTokens":10}}""")
                } else {
                    JsonObject(emptyMap())
                }
            }
            val session = open()
            session.send(Prompt("hi"))
            val events = session.turn()

            assertEquals("hm", events.filterIsInstance<AgentEvent.Thinking>().single().text)
            assertEquals("Hello", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.text })
            val calls = events.filterIsInstance<AgentEvent.ToolCall>()
            assertEquals(listOf("Read A.kt", "Read A.kt"), calls.map { it.title })
            assertEquals(
                "A.kt",
                calls
                    .last()
                    .args["path"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("class A", events.filterIsInstance<AgentEvent.ToolResult>().single().output)
            val plan = events.filterIsInstance<AgentEvent.PlanUpdated>().single().plan
            assertEquals(listOf(StepStatus.DONE, StepStatus.RUNNING), plan.steps.map { it.status })
            val costs = events.filterIsInstance<AgentEvent.Cost>()
            assertEquals(0.5, costs.first().usd)
            assertEquals(7L, costs.last().inputTokens)
            assertEquals(StopReason.END_TURN, assertIs<AgentEvent.Done>(events.last()).reason)
        }

    @Test
    fun `prompt carries attachments the agent can accept`() =
        test {
            val agent = agent { _, _ -> json("""{"stopReason":"end_turn"}""") }
            val session = open()
            session.send(
                Prompt(
                    "explain",
                    listOf(Attachment.Text("file:///A.kt", "class A", "text/x-kotlin"), Attachment.Image("image/png", "AAAA")),
                ),
            )
            session.turn()
            val blocks = agent.requested("session/prompt").single()["prompt"] as JsonArray
            assertEquals(listOf("resource", "image", "text"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        }

    @Test
    fun `permission request becomes an approval and the decision selects the matching option`() =
        test {
            var outcome: JsonObject? = null
            agent { method, _ ->
                if (method == "session/prompt") {
                    val response =
                        peer.request(
                            "session/request_permission",
                            json(
                                """{"sessionId":"s-1","toolCall":{"toolCallId":"t9","title":"Edit A.kt","kind":"edit",
                                "content":[{"type":"diff","path":"/work/A.kt","oldText":"a","newText":"b"}]},
                                "options":[{"optionId":"yes","name":"Allow","kind":"allow_once"},
                                {"optionId":"no","name":"Reject","kind":"reject_once"}]}""",
                            ),
                        )
                    outcome = response.jsonObject["outcome"]!!.jsonObject
                    json("""{"stopReason":"end_turn"}""")
                } else {
                    JsonObject(emptyMap())
                }
            }
            val session = open()
            session.send(Prompt("edit"))
            val seen = ArrayList<AgentEvent>()
            session.events
                .takeWhile { event ->
                    seen += event
                    if (event is AgentEvent.ApprovalRequest) session.approve(event.requestId, Decision.ALLOW_ONCE)
                    event !is AgentEvent.Done
                }.toList()

            val approval = seen.filterIsInstance<AgentEvent.ApprovalRequest>().single()
            assertEquals(ActionKind.EDIT_FILE, approval.action)
            assertEquals("Edit A.kt", approval.description)
            assertEquals("/work/A.kt", approval.changes.single().path)
            assertEquals("yes", outcome!!["optionId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `cancel rejects pending approvals and notifies the agent`() =
        test {
            var outcome: String? = null
            val agent =
                agent { method, _ ->
                    if (method == "session/prompt") {
                        val response =
                            peer.request(
                                "session/request_permission",
                                json("""{"sessionId":"s-1","toolCall":{},"options":[]}"""),
                            )
                        outcome =
                            response.jsonObject["outcome"]!!
                                .jsonObject["outcome"]!!
                                .jsonPrimitive.content
                        json("""{"stopReason":"cancelled"}""")
                    } else {
                        JsonObject(emptyMap())
                    }
                }
            val session = open()
            session.send(Prompt("go"))
            val seen = ArrayList<AgentEvent>()
            session.events
                .takeWhile { event ->
                    seen += event
                    if (event is AgentEvent.ApprovalRequest) session.cancel()
                    event !is AgentEvent.Done
                }.toList()

            assertEquals("cancelled", outcome)
            assertEquals(StopReason.CANCELLED, (seen.last() as AgentEvent.Done).reason)
            assertEquals("session/cancel", agent.notifications.single().first)
        }

    @Test
    fun `file system requests are served by the client file system`() =
        test {
            val written = HashMap<String, String>()
            val fs =
                object : ClientFileSystem {
                    override suspend fun readTextFile(
                        path: String,
                        line: Int?,
                        limit: Int?,
                    ) = "content of $path from $line limit $limit"

                    override suspend fun writeTextFile(
                        path: String,
                        content: String,
                    ) {
                        if (path.endsWith("denied")) throw IOException("Write rejected by the user")
                        written[path] = content
                    }
                }
            var read: String? = null
            var rejection: String? = null
            agent { method, _ ->
                if (method == "session/prompt") {
                    read =
                        peer
                            .request("fs/read_text_file", json("""{"sessionId":"s-1","path":"/a","line":3,"limit":2}"""))
                            .jsonObject["content"]!!
                            .jsonPrimitive.content
                    peer.request("fs/write_text_file", json("""{"sessionId":"s-1","path":"/b","content":"new"}"""))
                    rejection =
                        assertFailsWith<JsonRpcException> {
                            peer.request("fs/write_text_file", json("""{"sessionId":"s-1","path":"/denied","content":"x"}"""))
                        }.message
                    json("""{"stopReason":"end_turn"}""")
                } else {
                    JsonObject(emptyMap())
                }
            }
            val session = open(SessionConfig(cwd = "/work", fileSystem = fs))
            session.send(Prompt("go"))
            session.turn()

            assertEquals("content of /a from 3 limit 2", read)
            assertEquals(mapOf("/b" to "new"), written)
            assertEquals("Write rejected by the user", rejection)
        }

    @Test
    fun `second prompt while a turn runs is refused`() =
        test {
            agent { method, _ ->
                if (method == "session/prompt") delay(Long.MAX_VALUE)
                JsonObject(emptyMap())
            }
            val session = open()
            session.send(Prompt("one"))
            assertFailsWith<IllegalStateException> { session.send(Prompt("two")) }
        }

    @Test
    fun `auth error on session creation is reported as auth required with the agent message`() =
        test {
            FakeAgent(pipe.agent, scope) { method, _ ->
                when (method) {
                    "initialize" -> json("""{"protocolVersion":1,"authMethods":[{"id":"x","name":"Login","description":"Run login"}]}""")
                    else -> throw JsonRpcException(-32000, "Authentication required")
                }
            }
            val error = assertFailsWith<AgentAuthRequiredException> { open() }
            assertEquals(listOf("Run login"), error.methods)
            assertTrue("Authentication required" in error.message!!)
            assertTrue(spec.loginHint in error.message!!)
        }

    @Test
    fun `unsupported protocol version and early exit fail the start`() {
        test {
            agent(init = json("""{"protocolVersion":2}"""))
            assertTrue("ACP v2" in assertFailsWith<AgentStartException> { open() }.message!!)
        }
    }

    @Test
    fun `agent exiting during startup fails the start`() =
        test {
            pipe.agent.close()
            assertFailsWith<AgentStartException> { open() }
        }

    @Test
    fun `agent exiting mid session emits an error and completes the events`() =
        test {
            agent()
            val session = open()
            pipe.agent.close()
            val events = session.events.toList()
            assertTrue(assertIs<AgentEvent.Error>(events.single()).message.contains("exited unexpectedly"))
        }

    @Test
    fun `close is idempotent and completes the events without an error`() =
        test {
            agent()
            val session = open()
            session.close()
            session.close()
            assertTrue(session.events.toList().isEmpty())
        }
}
