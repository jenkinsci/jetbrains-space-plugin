package org.jetbrains.space.jenkins.steps

import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution

/**
 * Synchronously completes pipeline step execution and reports its failure by posting a provided error message
 */
class FailureStepExecution(private val message: String, context: StepContext) : StepExecution(context) {
    override fun start(): Boolean {
        val ex: Throwable = IllegalArgumentException(message)
        ex.stackTrace = arrayOfNulls(0)
        context.onFailure(ex)
        return true
    }
}