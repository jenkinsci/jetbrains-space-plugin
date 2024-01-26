package org.jetbrains.space.jenkins

/**
 * Contains constants for environment variable names specific to JetBrains Space.
 */
object Env {
    const val SPACE_URL = "SPACE_URL"
    const val PROJECT_KEY = "SPACE_PROJECT_KEY"
    const val REPOSITORY_NAME = "SPACE_REPOSITORY_NAME"

    const val MERGE_REQUEST_ID = "SPACE_MERGE_REQUEST_ID"
    const val MERGE_REQUEST_NUMBER = "SPACE_MERGE_REQUEST_NUMBER"
    const val MERGE_REQUEST_SOURCE_BRANCH = "SPACE_MERGE_REQUEST_SOURCE_BRANCH"
    const val MERGE_REQUEST_TARGET_BRANCH = "SPACE_MERGE_REQUEST_TARGET_BRANCH"
    const val MERGE_REQUEST_TITLE = "SPACE_MERGE_REQUEST_TITLE"
    const val MERGE_REQUEST_URL = "SPACE_MERGE_REQUEST_URL"
}