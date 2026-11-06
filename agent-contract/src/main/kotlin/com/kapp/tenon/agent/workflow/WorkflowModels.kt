// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.workflow

import kotlinx.serialization.Serializable

@Serializable
enum class WorkflowStepType {
    AGENT,
    GATE,
}

@Serializable
data class WorkflowRetryPolicy(
    val maxRetries: Int = 3,
    val feedbackTo: String? = null,
    val feedbackPrompt: String? = null,
)

@Serializable
data class WorkflowCardDef(
    val kind: String = "ask",
    val title: String,
    val text: String,
    val options: List<String> = emptyList(),
    val recommend: Int = 0,
)

@Serializable
data class WorkflowStep(
    val id: String,
    val name: String,
    val type: WorkflowStepType,
    val agentAlias: String? = null,
    val inputTemplate: String? = null,
    val outputKey: String? = null,
    val command: List<String> = emptyList(),
    val retryPolicy: WorkflowRetryPolicy? = null,
    val card: WorkflowCardDef? = null,
    val condition: String? = null,
)

@Serializable
data class WorkflowDefinition(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val steps: List<WorkflowStep> = emptyList(),
)

@Serializable
enum class WorkflowStatus {
    IDLE,
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
data class WorkflowExecutionState(
    val workflowId: String,
    val definition: WorkflowDefinition,
    val status: WorkflowStatus = WorkflowStatus.IDLE,
    val currentStepIndex: Int = 0,
    val context: Map<String, String> = emptyMap(),
    val suspendedStepId: String? = null,
    val pendingCard: WorkflowCardDef? = null,
    val error: String? = null,
    val stepRetries: Map<String, Int> = emptyMap(),
)
