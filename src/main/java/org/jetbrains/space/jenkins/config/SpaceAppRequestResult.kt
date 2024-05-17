package org.jetbrains.space.jenkins.config

import io.ktor.http.*

/**
 * Represents the current state of the SpaceCode application with its permissions obtained from SpaceCode
 */
sealed class SpaceAppRequestResult

/**
 * SpaceCode application info and its granted and missing permissions.
 */
class SpaceAppInfo(
    val appId: String,
    val appName: String,
    val appClientId: String,
    val managePermissionsUrl: String,
    val permissions: List<String>,
    val missingPermissions: List<String>
) : SpaceAppRequestResult()

/**
 * Represents an error while requesting application status from SpaceCode
 *
 * @property statusCode The HTTP status code to return in response to fetch application status request
 * @property message The error message.
 * @property clientId The client ID of the SpaceCode application, if known
 */
class SpaceAppRequestError(val statusCode: Int, val message: String, val clientId: String?): SpaceAppRequestResult()
