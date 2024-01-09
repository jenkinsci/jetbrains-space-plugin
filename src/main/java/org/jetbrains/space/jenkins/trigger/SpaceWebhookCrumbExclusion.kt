package org.jetbrains.space.jenkins.trigger

import hudson.Extension
import hudson.security.csrf.CrumbExclusion
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Adds exception to the CSRF protection filter for [SpaceWebhookEndpoint].
 */
@Extension
class SpaceWebhookCrumbExclusion : CrumbExclusion() {

    override fun process(req: HttpServletRequest, resp: HttpServletResponse, chain: FilterChain): Boolean {
        val pathInfo = req.pathInfo
        if (pathInfo.isBlank() || !pathInfo.startsWith(EXCLUSION_PATH)) {
            return false
        }
        chain.doFilter(req, resp)
        return true
    }

    companion object {
        private const val EXCLUSION_PATH = "/${SpaceWebhookEndpoint.URL}/"
    }
}
