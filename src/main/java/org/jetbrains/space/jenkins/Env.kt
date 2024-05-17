package org.jetbrains.space.jenkins

/**
 * Contains constants for environment variable names specific to JetBrains SpaceCode.
 */
object Env {
    const val SPACE_URL = "SPACECODE_URL"
    @field:SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    const val PROJECT_KEY = "SPACECODE_PROJECT_KEY"
    const val REPOSITORY_NAME = "SPACECODE_REPOSITORY_NAME"

    const val MERGE_REQUEST_ID = "MERGE_REQUEST_ID"
    const val MERGE_REQUEST_NUMBER = "MERGE_REQUEST_NUMBER"
    const val MERGE_REQUEST_SOURCE_BRANCH = "MERGE_REQUEST_SOURCE_BRANCH"
    const val MERGE_REQUEST_TARGET_BRANCH = "MERGE_REQUEST_TARGET_BRANCH"
    const val MERGE_REQUEST_TITLE = "MERGE_REQUEST_TITLE"
    const val MERGE_REQUEST_URL = "MERGE_REQUEST_URL"

    const val IS_SAFE_MERGE = "IS_SAFE_MERGE"
    const val IS_DRY_RUN = "IS_DRY_RUN"
    const val SAFE_MERGE_STARTED_BY_USER_ID = "SAFE_MERGE_STARTED_BY_USER_ID"
}