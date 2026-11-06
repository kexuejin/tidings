// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.workflow.WorkflowCardDef
import com.kapp.tenon.agent.workflow.WorkflowDefinition
import com.kapp.tenon.agent.workflow.WorkflowExecutionState
import com.kapp.tenon.agent.workflow.WorkflowStatus
import com.kapp.tenon.agent.workflow.WorkflowStep
import com.kapp.tenon.agent.workflow.WorkflowStepType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface WorkflowStepRunner {
    suspend fun runAgentStep(
        step: WorkflowStep,
        resolvedInput: String,
        context: Map<String, String>,
    ): WorkflowStepResult
}

sealed interface WorkflowStepResult {
    data class Success(val output: String) : WorkflowStepResult
    data class Failure(val error: String) : WorkflowStepResult
}

sealed interface WorkflowProgress {
    data class StepStarted(
        val stepId: String,
        val name: String,
        val stepIndex: Int,
        val totalSteps: Int,
    ) : WorkflowProgress

    data class StepCompleted(
        val stepId: String,
        val output: String,
    ) : WorkflowProgress

    data class StepSkipped(
        val stepId: String,
        val reason: String,
    ) : WorkflowProgress

    data class Suspended(
        val workflowId: String,
        val stepId: String,
        val card: WorkflowCardDef,
    ) : WorkflowProgress

    data class Completed(
        val workflowId: String,
        val outputs: Map<String, String>,
    ) : WorkflowProgress

    data class Failed(
        val workflowId: String,
        val stepId: String,
        val error: String,
    ) : WorkflowProgress
}

/**
 * Declarative workflow playbook engine.
 *
 * Implements:
 * - Dynamic template interpolation ({input.x}, {steps.y.output}, {steps.z.choice}, {error}).
 * - Gate zero-cost suspension and callback resumption.
 * - Condition-based step branching.
 * - Self-healing retry loops with error feedback prompts.
 */
class WorkflowPluginEngine(
    private val stepRunner: WorkflowStepRunner,
) {
    private val executions = ConcurrentHashMap<String, WorkflowExecutionState>()

    fun getExecution(workflowId: String): WorkflowExecutionState? = executions[workflowId]

    fun restoreExecution(state: WorkflowExecutionState) {
        executions[state.workflowId] = state
    }

    fun cancel(workflowId: String): Boolean {
        val existing = executions[workflowId] ?: return false
        executions[workflowId] = existing.copy(status = WorkflowStatus.CANCELLED)
        return true
    }

    suspend fun start(
        definition: WorkflowDefinition,
        initialInputs: Map<String, String> = emptyMap(),
        workflowId: String = UUID.randomUUID().toString(),
        onProgress: (suspend (WorkflowProgress) -> Unit)? = null,
    ): WorkflowProgress {
        val initialContext = mutableMapOf<String, String>()
        initialInputs.forEach { (k, v) ->
            initialContext[k] = v
            if (!k.startsWith("input.")) {
                initialContext["input.$k"] = v
            }
        }

        val initialState = WorkflowExecutionState(
            workflowId = workflowId,
            definition = definition,
            status = WorkflowStatus.RUNNING,
            currentStepIndex = 0,
            context = initialContext,
        )
        executions[workflowId] = initialState
        return executeLoop(initialState, onProgress)
    }

    suspend fun resumeGate(
        workflowId: String,
        stepId: String,
        choice: Int,
        note: String? = null,
        onProgress: (suspend (WorkflowProgress) -> Unit)? = null,
    ): WorkflowProgress {
        val existing = executions[workflowId]
            ?: return WorkflowProgress.Failed(workflowId, stepId, "workflow not found: $workflowId")

        if (existing.status != WorkflowStatus.SUSPENDED || existing.suspendedStepId != stepId) {
            return WorkflowProgress.Failed(
                workflowId,
                stepId,
                "workflow is not suspended at step $stepId (status: ${existing.status}, suspendedStep: ${existing.suspendedStepId})",
            )
        }

        val updatedContext = existing.context.toMutableMap()
        updatedContext["steps.$stepId.choice"] = choice.toString()
        if (!note.isNullOrBlank()) {
            updatedContext["steps.$stepId.note"] = note
        }

        val resumedState = existing.copy(
            status = WorkflowStatus.RUNNING,
            suspendedStepId = null,
            pendingCard = null,
            currentStepIndex = existing.currentStepIndex + 1,
            context = updatedContext,
        )
        executions[workflowId] = resumedState
        return executeLoop(resumedState, onProgress)
    }

    private suspend fun executeLoop(
        initialState: WorkflowExecutionState,
        onProgress: (suspend (WorkflowProgress) -> Unit)?,
    ): WorkflowProgress {
        var state = initialState
        val definition = state.definition

        while (state.currentStepIndex < definition.steps.size) {
            val step = definition.steps[state.currentStepIndex]

            // Condition evaluation
            if (!evaluateCondition(step.condition, state.context)) {
                val skipped = WorkflowProgress.StepSkipped(
                    step.id,
                    "condition not met: ${step.condition}",
                )
                onProgress?.invoke(skipped)
                state = state.copy(currentStepIndex = state.currentStepIndex + 1)
                executions[state.workflowId] = state
                continue
            }

            when (step.type) {
                WorkflowStepType.GATE -> {
                    val cardDef = step.card ?: WorkflowCardDef(
                        title = step.name,
                        text = "Approval required",
                        options = listOf("Approve", "Reject"),
                    )
                    val resolvedCard = cardDef.copy(
                        title = resolveTemplate(cardDef.title, state.context),
                        text = resolveTemplate(cardDef.text, state.context),
                    )
                    state = state.copy(
                        status = WorkflowStatus.SUSPENDED,
                        suspendedStepId = step.id,
                        pendingCard = resolvedCard,
                    )
                    executions[state.workflowId] = state

                    val suspendedProgress = WorkflowProgress.Suspended(state.workflowId, step.id, resolvedCard)
                    onProgress?.invoke(suspendedProgress)
                    return suspendedProgress
                }

                WorkflowStepType.AGENT -> {
                    val started = WorkflowProgress.StepStarted(
                        stepId = step.id,
                        name = step.name,
                        stepIndex = state.currentStepIndex,
                        totalSteps = definition.steps.size,
                    )
                    onProgress?.invoke(started)

                    val initialInput = step.inputTemplate?.let { resolveTemplate(it, state.context) } ?: ""
                    var currentInput = initialInput
                    var stepSuccess = false
                    var finalOutput = ""
                    var lastError = ""
                    val maxRetries = step.retryPolicy?.maxRetries ?: 0
                    var attempts = 0

                    while (!stepSuccess && attempts <= maxRetries) {
                        attempts++
                        val result = stepRunner.runAgentStep(step, currentInput, state.context)
                        when (result) {
                            is WorkflowStepResult.Success -> {
                                stepSuccess = true
                                finalOutput = result.output
                            }

                            is WorkflowStepResult.Failure -> {
                                lastError = result.error
                                val currentRetries = (state.stepRetries[step.id] ?: 0) + 1
                                val updatedRetries = state.stepRetries + (step.id to currentRetries)
                                state = state.copy(stepRetries = updatedRetries)
                                executions[state.workflowId] = state

                                val retryPolicy = step.retryPolicy
                                if (attempts <= maxRetries && retryPolicy != null) {
                                    val promptTemplate = retryPolicy.feedbackPrompt
                                        ?: "Previous attempt failed: {error}"
                                    val retryCtx = state.context + mapOf("error" to lastError)
                                    currentInput = resolveTemplate(promptTemplate, retryCtx)
                                }
                            }
                        }
                    }

                    if (!stepSuccess) {
                        state = state.copy(
                            status = WorkflowStatus.FAILED,
                            error = lastError,
                        )
                        executions[state.workflowId] = state
                        val failedProgress = WorkflowProgress.Failed(state.workflowId, step.id, lastError)
                        onProgress?.invoke(failedProgress)
                        return failedProgress
                    }

                    val updatedContext = state.context.toMutableMap()
                    updatedContext["steps.${step.id}.output"] = finalOutput
                    val outputKey = step.outputKey
                    if (!outputKey.isNullOrBlank()) {
                        updatedContext[outputKey] = finalOutput
                    }
                    state = state.copy(
                        currentStepIndex = state.currentStepIndex + 1,
                        context = updatedContext,
                    )
                    executions[state.workflowId] = state

                    val stepCompleted = WorkflowProgress.StepCompleted(step.id, finalOutput)
                    onProgress?.invoke(stepCompleted)
                }
            }
        }

        state = state.copy(status = WorkflowStatus.COMPLETED)
        executions[state.workflowId] = state
        val completedProgress = WorkflowProgress.Completed(state.workflowId, state.context)
        onProgress?.invoke(completedProgress)
        return completedProgress
    }

    companion object {
        private val PLACEHOLDER_REGEX = Regex("""\{([^{}]+)\}""")

        fun resolveTemplate(template: String, context: Map<String, String>): String {
            return PLACEHOLDER_REGEX.replace(template) { matchResult ->
                val placeholder = matchResult.groupValues[1].trim()
                lookupContextValue(placeholder, context) ?: matchResult.value
            }
        }

        private fun lookupContextValue(key: String, context: Map<String, String>): String? {
            context[key]?.let { return it }
            if (!key.startsWith("input.")) {
                context["input.$key"]?.let { return it }
            }
            if (key.startsWith("input.")) {
                context[key.removePrefix("input.")]?.let { return it }
            }
            if (key.startsWith("steps.")) {
                val shortKey = key.removePrefix("steps.")
                context[shortKey]?.let { return it }
            }
            return null
        }

        fun evaluateCondition(condition: String?, context: Map<String, String>): Boolean {
            if (condition.isNullOrBlank()) return true
            val resolved = resolveTemplate(condition.trim(), context).trim()

            if (resolved.contains("==")) {
                val parts = resolved.split("==", limit = 2)
                val left = parts[0].trim().trim('"', '\'')
                val right = parts[1].trim().trim('"', '\'')
                return left == right
            }

            if (resolved.contains("!=")) {
                val parts = resolved.split("!=", limit = 2)
                val left = parts[0].trim().trim('"', '\'')
                val right = parts[1].trim().trim('"', '\'')
                return left != right
            }

            return resolved.toBooleanStrictOrNull() ?: false
        }
    }
}
