// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.workflow

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorkflowModelsSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun serializesAndDeserializesSampleWorkflow() {
        val workflowJson = """
            {
              "name": "code-auto-fix",
              "version": "1.0.0",
              "description": "Auto diagnose and fix pipeline",
              "steps": [
                {
                  "id": "analyze",
                  "name": "analyze-root-cause",
                  "type": "AGENT",
                  "agentAlias": "diagnoser",
                  "inputTemplate": "Please analyze issue:\n{input.issueText}"
                },
                {
                  "id": "fix",
                  "name": "write-patch",
                  "type": "AGENT",
                  "agentAlias": "coder",
                  "inputTemplate": "Based on report:\n{steps.analyze.output}\nWrite patch",
                  "outputKey": "patchDiff"
                },
                {
                  "id": "test",
                  "name": "run-tests",
                  "type": "AGENT",
                  "agentAlias": "tester",
                  "command": ["./gradlew", "test"],
                  "retryPolicy": {
                    "maxRetries": 3,
                    "feedbackTo": "coder",
                    "feedbackPrompt": "Tests failed:\n{error}\nPlease fix"
                  }
                },
                {
                  "id": "review-gate",
                  "name": "human-gate",
                  "type": "GATE",
                  "card": {
                    "kind": "ask",
                    "title": "Tests green, approve merge?",
                    "text": "Patch passed all tests.",
                    "options": ["Approve", "Reject", "Abandon"],
                    "recommend": 0
                  }
                },
                {
                  "id": "merge",
                  "name": "merge-main",
                  "type": "AGENT",
                  "agentAlias": "tester",
                  "condition": "{steps.review-gate.choice} == 0",
                  "command": ["git", "merge", "--ff-only", "fix-branch"]
                }
              ]
            }
        """.trimIndent()

        val def = json.decodeFromString<WorkflowDefinition>(workflowJson)
        assertEquals("code-auto-fix", def.name)
        assertEquals(5, def.steps.size)

        assertEquals("analyze", def.steps[0].id)
        assertEquals(WorkflowStepType.AGENT, def.steps[0].type)
        assertEquals("diagnoser", def.steps[0].agentAlias)

        assertEquals("fix", def.steps[1].id)
        assertEquals("patchDiff", def.steps[1].outputKey)

        assertEquals("test", def.steps[2].id)
        assertNotNull(def.steps[2].retryPolicy)
        assertEquals(3, def.steps[2].retryPolicy?.maxRetries)
        assertEquals("coder", def.steps[2].retryPolicy?.feedbackTo)

        assertEquals("review-gate", def.steps[3].id)
        assertEquals(WorkflowStepType.GATE, def.steps[3].type)
        assertNotNull(def.steps[3].card)
        assertEquals(3, def.steps[3].card?.options?.size)

        assertEquals("merge", def.steps[4].id)
        assertEquals("{steps.review-gate.choice} == 0", def.steps[4].condition)

        val encoded = json.encodeToString(def)
        val decodedAgain = json.decodeFromString<WorkflowDefinition>(encoded)
        assertEquals(def, decodedAgain)
    }

    @Test
    fun serializesAndDeserializesExecutionState() {
        val def = WorkflowDefinition(
            name = "test-flow",
            steps = listOf(
                WorkflowStep(id = "s1", name = "step-1", type = WorkflowStepType.AGENT),
            ),
        )
        val state = WorkflowExecutionState(
            workflowId = "wf-123",
            definition = def,
            status = WorkflowStatus.SUSPENDED,
            currentStepIndex = 1,
            context = mapOf("input.text" to "hello", "steps.s1.output" to "world"),
            suspendedStepId = "s2",
            pendingCard = WorkflowCardDef(title = "Gate", text = "Approval needed"),
            error = null,
            stepRetries = mapOf("s1" to 1),
        )

        val jsonStr = json.encodeToString(state)
        val restored = json.decodeFromString<WorkflowExecutionState>(jsonStr)
        assertEquals(state, restored)
    }
}
