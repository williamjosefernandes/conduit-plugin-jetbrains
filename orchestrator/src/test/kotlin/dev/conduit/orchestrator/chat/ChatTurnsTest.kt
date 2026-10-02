package dev.conduit.orchestrator.chat

import dev.conduit.core.AgentEvent
import dev.conduit.core.FileChange
import dev.conduit.core.FileChangeStatus
import dev.conduit.core.Plan
import dev.conduit.core.SessionId
import dev.conduit.core.Step
import dev.conduit.core.StepId
import dev.conduit.core.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatTurnsTest {
    private val id = SessionId("s")

    private fun plan(status: StepStatus) = Plan(listOf(Step(StepId("1"), "Analyse", status)))

    @Test
    fun `one plan card per turn, revised in place`() {
        var state = ChatState().userSaid("first", at = 5)
        state = state.reduce(AgentEvent.Text(id, "Sure."), 7)
        state = state.reduce(AgentEvent.PlanUpdated(id, plan(StepStatus.RUNNING)))
        state = state.reduce(AgentEvent.Text(id, "Done."), 9)
        state = state.reduce(AgentEvent.PlanUpdated(id, plan(StepStatus.DONE)))
        val cards = state.items.filterIsInstance<ChatItem.PlanCard>()
        assertEquals(1, cards.size)
        assertEquals(
            StepStatus.DONE,
            cards
                .single()
                .plan.steps
                .single()
                .status,
        )
        assertIs<ChatItem.PlanCard>(state.items[2])
        assertEquals(5, (state.items[0] as ChatItem.UserMessage).at)
        assertEquals(7, (state.items[1] as ChatItem.AssistantMessage).at)

        state = state.userSaid("second").reduce(AgentEvent.PlanUpdated(id, plan(StepStatus.PENDING)))
        assertEquals(2, state.items.count { it is ChatItem.PlanCard })
    }

    @Test
    fun `turn changes are recorded once and change state`() {
        val file = FileChange("a.kt", FileChangeStatus.MODIFIED, 1, 1)
        var state = ChatState().userSaid("go")
        val turn = state.lastTurn!!.id
        state = state.turnChanges(turn, listOf(file)).turnChanges(turn, listOf(file, file.copy(path = "b.kt")))
        val changes = state.items.filterIsInstance<ChatItem.TurnChanges>().single()
        assertEquals(2, changes.files.size)
        state = state.turnChangesState(turn, TurnChangesState.REVERTED)
        assertEquals(
            TurnChangesState.REVERTED,
            state.items
                .filterIsInstance<ChatItem.TurnChanges>()
                .single()
                .state,
        )
        val nothing = ChatState().userSaid("go")
        assertEquals(nothing, nothing.turnChanges(nothing.lastTurn!!.id, emptyList()))
    }

    @Test
    fun `conversations survive a round trip through the history format`() {
        val state =
            ChatState()
                .userSaid("hello", listOf("Main.kt"), 1)
                .reduce(AgentEvent.Text(id, "hi"), 2)
                .reduce(AgentEvent.Cost(id, usd = 0.5))
                .reduce(AgentEvent.Done(id, dev.conduit.core.StopReason.END_TURN))
        val stored = StoredConversation("c", "claude-code", "Claude Code", "hello", 1, 2, "token", state)
        assertEquals(stored, StoredConversation.fromJson(stored.toJson()))
    }
}
