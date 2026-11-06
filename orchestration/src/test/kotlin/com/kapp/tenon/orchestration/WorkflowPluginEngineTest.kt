// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.workflow.WorkflowCardDef
import com.kapp.tenon.agent.workflow.WorkflowDefinition
import com.kapp.tenon.agent.workflow.WorkflowExecutionState
import com.kapp.tenon.agent.workflow.WorkflowRetryPolicy
import com.kapp.tenon.agent.workflow.WorkflowStatus
import com.kapp.tenon.agent.workflow.WorkflowStep
import com.kapp.tenon.agent.workflow.WorkflowStepType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowPluginEngineTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun templateInterpolationAndContextLookup() {
        val ctx = mapOf(
            "input.issueText" to "Memory leak in queue",
            "steps.analyze.output" to "Leak identified in buffer",
            "steps.gate.choice" to "0",
            "error" to "NullPointerException",
        )

        val text = WorkflowPluginEngine.resolveTemplate(
            "Issue: {input.issueText}, RootCause: {steps.analyze.output}",
            ctx,
        )
        assertEquals("Issue: Memory leak in queue, RootCause: Leak identified in buffer", text)

        val errPrompt = WorkflowPluginEngine.resolveTemplate("Retry after: {error}", ctx)
        assertEquals("Retry after: NullPointerException", errPrompt)
    }

    @Test
    fun conditionEvaluation() {
        val ctx = mapOf(
            "steps.review-gate.choice" to "0",
            "steps.verify.output" to "passed",
        )

        assertTrue(WorkflowPluginEngine.evaluateCondition(null, ctx))
        assertTrue(WorkflowPluginEngine.evaluateCondition("{steps.review-gate.choice} == 0", ctx))
        assertTrue(WorkflowPluginEngine.evaluateCondition("{steps.review-gate.choice} != 1", ctx))
        assertTrue(WorkflowPluginEngine.evaluateCondition("{steps.verify.output} == passed", ctx))
        assertTrue(!WorkflowPluginEngine.evaluateCondition("{steps.review-gate.choice} == 1", ctx))
    }

    @Test
    fun sequentialTwoStepExecution(): Unit = runBlocking {
        val def = WorkflowDefinition(
            name = "two-step",
            steps = listOf(
                WorkflowStep(
                    id = "step1",
                    name = "Step One",
                    type = WorkflowStepType.AGENT,
                    inputTemplate = "Input: {input.text}",
                ),
                WorkflowStep(
                    id = "step2",
                    name = "Step Two",
                    type = WorkflowStepType.AGENT,
                    inputTemplate = "Previous output: {steps.step1.output}",
                ),
            ),
        )

        val stepRunner = object : WorkflowStepRunner {
            override suspend fun runAgentStep(
                step: WorkflowStep,
                resolvedInput: String,
                context: Map<String, String>,
            ): WorkflowStepResult {
                return when (step.id) {
                    "step1" -> WorkflowStepResult.Success("output-from-1")
                    "step2" -> WorkflowStepResult.Success("output-from-2 ($resolvedInput)")
                    else -> WorkflowStepResult.Failure("Unknown step: ${step.id}")
                }
            }
        }

        val engine = WorkflowPluginEngine(stepRunner)
        val result = engine.start(def, mapOf("text" to "hello"))

        assertIs<WorkflowProgress.Completed>(result)
        assertEquals("output-from-1", result.outputs["steps.step1.output"])
        assertEquals("output-from-2 (Previous output: output-from-1)", result.outputs["steps.step2.output"])

        val execution = engine.getExecution(result.workflowId)
        assertNotNull(execution)
        assertEquals(WorkflowStatus.COMPLETED, execution.status)
    }

    @Test
    fun zeroCostGateSuspensionAndResume(): Unit = runBlocking {
        val def = WorkflowDefinition(
            name = "gate-flow",
            steps = listOf(
                WorkflowStep(
                    id = "analyze",
                    name = "Analyze",
                    type = WorkflowStepType.AGENT,
                ),
                WorkflowStep(
                    id = "gate",
                    name = "Approve Merge",
                    type = WorkflowStepType.GATE,
                    card = WorkflowCardDef(
                        title = "Approve for {input.targetBranch}",
                        text = "Result: {steps.analyze.output}",
                        options = listOf("Approve", "Reject"),
                    ),
                ),
                WorkflowStep(
                    id = "merge",
                    name = "Merge Code",
                    type = WorkflowStepType.AGENT,
                    condition = "{steps.gate.choice} == 0",
                ),
            ),
        )

        val stepRunner = object : WorkflowStepRunner {
            override suspend fun runAgentStep(
                step: WorkflowStep,
                resolvedInput: String,
                context: Map<String, String>,
            ): WorkflowStepResult {
                return when (step.id) {
                    "analyze" -> WorkflowStepResult.Success("analysis-ok")
                    "merge" -> WorkflowStepResult.Success("merged-cleanly")
                    else -> WorkflowStepResult.Failure("unexpected")
                }
            }
        }

        val engine = WorkflowPluginEngine(stepRunner)
        val initialProgress = engine.start(def, mapOf("targetBranch" to "main"))

        assertIs<WorkflowProgress.Suspended>(initialProgress)
        assertEquals("gate", initialProgress.stepId)
        assertEquals("Approve for main", initialProgress.card.title)
        assertEquals("Result: analysis-ok", initialProgress.card.text)

        val stateAfterSuspend = engine.getExecution(initialProgress.workflowId)
        assertNotNull(stateAfterSuspend)
        assertEquals(WorkflowStatus.SUSPENDED, stateAfterSuspend.status)
        assertEquals("gate", stateAfterSuspend.suspendedStepId)

        // Resume with Approve (choice = 0)
        val resumeProgress = engine.resumeGate(
            workflowId = initialProgress.workflowId,
            stepId = "gate",
            choice = 0,
            note = "Looks good to merge",
        )

        assertIs<WorkflowProgress.Completed>(resumeProgress)
        assertEquals("merged-cleanly", resumeProgress.outputs["steps.merge.output"])
        assertEquals("0", resumeProgress.outputs["steps.gate.choice"])
        assertEquals("Looks good to merge", resumeProgress.outputs["steps.gate.note"])
    }

    @Test
    fun gateRejectionSkipsConditionalStep(): Unit = runBlocking {
        val def = WorkflowDefinition(
            name = "rejection-flow",
            steps = listOf(
                WorkflowStep(
                    id = "gate",
                    name = "Review Gate",
                    type = WorkflowStepType.GATE,
                ),
                WorkflowStep(
                    id = "merge",
                    name = "Merge",
                    type = WorkflowStepType.AGENT,
                    condition = "{steps.gate.choice} == 0",
                ),
            ),
        )

        var mergeExecuted = false
        val stepRunner = object : WorkflowStepRunner {
            override suspend fun runAgentStep(
                step: WorkflowStep,
                resolvedInput: String,
                context: Map<String, String>,
            ): WorkflowStepResult {
                if (step.id == "merge") mergeExecuted = true
                return WorkflowStepResult.Success("ok")
            }
        }

        val engine = WorkflowPluginEngine(stepRunner)
        val suspended = engine.start(def)
        assertIs<WorkflowProgress.Suspended>(suspended)

        val skippedEvents = mutableListOf<WorkflowProgress.StepSkipped>()
        val finalProgress = engine.resumeGate(
            workflowId = suspended.workflowId,
            stepId = "gate",
            choice = 1, // Reject
            onProgress = {
                if (it is WorkflowProgress.StepSkipped) skippedEvents.add(it)
            },
        )

        assertIs<WorkflowProgress.Completed>(finalProgress)
        assertTrue(!mergeExecuted, "Conditional merge step should not have run")
        assertEquals(1, skippedEvents.size)
        assertEquals("merge", skippedEvents[0].stepId)
    }

    @Test
    fun selfHealingRetryRecovers(): Unit = runBlocking {
        val def = WorkflowDefinition(
            name = "retry-flow",
            steps = listOf(
                WorkflowStep(
                    id = "compile",
                    name = "Compile",
                    type = WorkflowStepType.AGENT,
                    retryPolicy = WorkflowRetryPolicy(
                        maxRetries = 2,
                        feedbackPrompt = "Compilation failed: {error}. Please fix.",
                    ),
                ),
            ),
        )

        var callCount = 0
        var receivedPromptOnRetry = ""

        val stepRunner = object : WorkflowStepRunner {
            override suspend fun runAgentStep(
                step: WorkflowStep,
                resolvedInput: String,
                context: Map<String, String>,
            ): WorkflowStepResult {
                callCount++
                if (callCount == 1) {
                    return WorkflowStepResult.Failure("Syntax error on token 'foo'")
                }
                receivedPromptOnRetry = resolvedInput
                return WorkflowStepResult.Success("Compilation succeeded")
            }
        }

        val engine = WorkflowPluginEngine(stepRunner)
        val result = engine.start(def)

        assertIs<WorkflowProgress.Completed>(result)
        assertEquals(2, callCount)
        assertEquals("Compilation failed: Syntax error on token 'foo'. Please fix.", receivedPromptOnRetry)
        assertEquals("Compilation succeeded", result.outputs["steps.compile.output"])
    }

    @Test
    fun retryExhaustionFailsWorkflow(): Unit = runBlocking {
        val def = WorkflowDefinition(
            name = "fail-flow",
            steps = listOf(
                WorkflowStep(
                    id = "test",
                    name = "Run Test",
                    type = WorkflowStepType.AGENT,
                    retryPolicy = WorkflowRetryPolicy(maxRetries = 1),
                ),
            ),
        )

        val stepRunner = object : WorkflowStepRunner {
            override suspend fun runAgentStep(
                step: WorkflowStep,
                resolvedInput: String,
                context: Map<String, String>,
            ): WorkflowStepResult {
                return WorkflowStepResult.Failure("Assert failed: expected 1 got 2")
            }
        }

        val engine = WorkflowPluginEngine(stepRunner)
        val result = engine.start(def)

        assertIs<WorkflowProgress.Failed>(result)
        assertEquals("test", result.stepId)
        assertTrue(result.error.contains("Assert failed"))

        val exec = engine.getExecution(result.workflowId)
        assertNotNull(exec)
        assertEquals(WorkflowStatus.FAILED, exec.status)
        assertEquals(2, exec.stepRetries["test"]) // Initial + 1 retry
    }

    @Test
    fun restoreExecutionStateAcrossRestarts(): Unit = runBlocking {
        val def = WorkflowDefinition(
            name = "restart-flow",
            steps = listOf(
                WorkflowStep(id = "step1", name = "S1", type = WorkflowStepType.AGENT),
                WorkflowStep(id = "gate", name = "G", type = WorkflowStepType.GATE),
                WorkflowStep(id = "step2", name = "S2", type = WorkflowStepType.AGENT),
            ),
        )

        val stepRunner = object : WorkflowStepRunner {
            override suspend fun runAgentStep(
                step: WorkflowStep,
                resolvedInput: String,
                context: Map<String, String>,
            ): WorkflowStepResult = WorkflowStepResult.Success("ok-${step.id}")
        }

        val engine1 = WorkflowPluginEngine(stepRunner)
        val suspended = engine1.start(def)
        assertIs<WorkflowProgress.Suspended>(suspended)

        val savedState = engine1.getExecution(suspended.workflowId)
        assertNotNull(savedState)

        // Serialize state to JSON and deserialize back (simulating process restart)
        val jsonStr = json.encodeToString(savedState)
        val restoredState = json.decodeFromString<WorkflowExecutionState>(jsonStr)

        val engine2 = WorkflowPluginEngine(stepRunner)
        engine2.restoreExecution(restoredState)

        val resumed = engine2.resumeGate(suspended.workflowId, "gate", choice = 0)
        assertIs<WorkflowProgress.Completed>(resumed)
        assertEquals("ok-step1", resumed.outputs["steps.step1.output"])
        assertEquals("ok-step2", resumed.outputs["steps.step2.output"])
    }
}
