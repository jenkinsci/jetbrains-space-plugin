package org.jetbrains.space.jenkins.scm

import org.jetbrains.space.jenkins.trigger.SpaceWebhookTrigger
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pattern builder for Git wildcard and regex branch specifications.
 */
object BranchPattern {

    fun matchesPattern(patterns: Collection<String>, isRegex: Boolean, text: String): Boolean {
        return PatternBuilder.matcher(patterns, isRegex, RegexCache, ::matchText).matches(text)
    }

    fun matcher(patterns: Collection<String>, isRegex: Boolean): PatternBuilder.Matcher {
        return PatternBuilder.matcher(patterns, isRegex, RegexCache, ::matchText)
    }

    fun matcher(patterns: Collection<PatternHolder<*>>): PatternBuilder.Matcher {
        return PatternBuilder.matcher(patterns, RegexCache, ::matchText)
    }

    private fun matchText(matcher: PatternBuilder.Matcher, text: String): Boolean {
        return matcher.matches(text) || text.startsWith(REFS_HEADS_PREFIX) && matcher.matches(text.removePrefix(REFS_HEADS_PREFIX))
    }

    private val regexCache = ConcurrentHashMap<String, Regex>()

    private object RegexCache : PatternBuilder.Cache {
        override fun getOrCompute(pattern: String): Regex {
            return regexCache.computeIfAbsent(pattern) {
                Regex(pattern)
            }
        }
    }
}

/**
 * Pattern builder for wildcard and regex based specifications.
 */
object PatternBuilder {
    const val asterisk = "*"
    const val includePrefix = "+:"
    const val excludePrefix = "-:"
    const val commentPrefix = "#"

    internal val TRUE = object : PatternMatcher() {
        override fun matchesImpl(text: String) = true
        override fun toStringImpl() = asterisk
    }

    internal val FALSE = object : PatternMatcher() {
        override fun matchesImpl(text: String) = false
        override fun toStringImpl() = ""
    }

    val matchAll: Matcher = TRUE

    fun matcher(
        patterns: Collection<String>,
        isRegex: Boolean,
        cache: Cache,
        match: (Matcher, String) -> Boolean = { matcher, text -> matcher.matches(text) }
    ): Matcher {
        val patternsFlat = patterns.flatMap { it.lines() }.filter { it.isNotBlank() }
        return if (isRegex) matcher(patternsFlat.map { PatternHolder.RegexStr(it) }, cache, match)
        else matcher(patternsFlat.map { PatternHolder.Wildcard(it) }, cache, match)
    }

    fun String.trim() = this.trim { it <= ' ' }

    fun matcher(
        patterns: Collection<PatternHolder<*>>,
        cache: Cache,
        match: (Matcher, String) -> Boolean = { matcher, text -> matcher.matches(text) },
    ): Matcher {
        val normalized = patterns.map { it.normalize() }
        if (isMatchAllRule(patterns)) return TRUE

        val includes = ArrayList<PatternMatcher>()
        val excludes = ArrayList<PatternMatcher>()

        normalized.forEach {
            val (regexMatcher, include) = it.branchRegexMatcher(cache)
            if (regexMatcher != null) {
                if (include) includes.add(regexMatcher)
                else excludes.add(regexMatcher)
            }
        }

        return object : PatternMatcher() {
            override fun matchesImpl(text: String): Boolean {
                for (exclude in excludes) {
                    if (match(exclude, text)) return false
                }

                for (include in includes) {
                    if (match(include, text)) return true
                }

                return false
            }

            override fun toStringImpl(): String {
                return "{+:$includes,-:$excludes}"
            }
        }
    }

    private fun isMatchAllRule(patterns: Collection<PatternHolder<*>>): Boolean {
        return patterns.singleOrNull()?.let { pattern ->
            pattern is PatternHolder.WithRawString && pattern.value == asterisk
        } ?: false
    }

    internal fun normalize(s: String): String {
        return s.trim().trim('/')
    }

    interface Matcher {
        fun matches(text: String): Boolean
    }

    interface Cache {
        fun getOrCompute(pattern: String): Regex
    }

    internal abstract class PatternMatcher : Matcher {
        override fun matches(text: String): Boolean {
            return matchesImpl(normalize(text))
        }

        protected abstract fun matchesImpl(text: String): Boolean

        override fun toString(): String {
            return "PatternMatcher:" + toStringImpl()
        }

        protected abstract fun toStringImpl(): String
    }

    internal class RegexPatternMatcher(private val regex: Regex) : PatternMatcher() {
        override fun matchesImpl(text: String): Boolean {
            return regex.matches(text)
        }

        override fun toStringImpl(): String {
            return ":/" + regex.pattern + "/"
        }
    }

    internal class PrefixPatterMatcher(private val prefix: String) : PatternMatcher() {
        override fun matchesImpl(text: String): Boolean {
            return text.startsWith(prefix)
        }

        override fun toStringImpl(): String {
            return ":/$prefix*/"
        }
    }

    internal class ExactPatternMatcher(private val value: String) : PatternMatcher() {
        override fun matchesImpl(text: String): Boolean {
            return text == value
        }

        override fun toStringImpl(): String {
            return value
        }
    }
}

sealed class PatternHolder<SELF : PatternHolder<SELF>> {

    interface WithRawString {
        val value: String
    }

    internal abstract fun normalize(): SELF

    internal abstract fun branchRegexMatcher(cache: PatternBuilder.Cache): Pair<PatternBuilder.PatternMatcher?, Boolean>

    class Wildcard(override val value: String) : PatternHolder<Wildcard>(), WithRawString {
        override fun normalize() = Wildcard(PatternBuilder.normalize(value))

        override fun branchRegexMatcher(cache: PatternBuilder.Cache): Pair<PatternBuilder.PatternMatcher?, Boolean> {
            val (pattern, include) = value.parseBranchSpecLine()
            if (pattern != null) {
                if (pattern == PatternBuilder.asterisk) return PatternBuilder.TRUE to include

                if (simpleWildcardPattern.matches(pattern)) {
                    return if (pattern.endsWith(PatternBuilder.asterisk)) {
                        val prefix = pattern.substring(0..pattern.length - 2)
                        PatternBuilder.PrefixPatterMatcher(prefix)
                    } else {
                        PatternBuilder.ExactPatternMatcher(pattern)
                    } to include
                }

                val matcher = pattern.wrapCompileErrors(true) {
                    PatternBuilder.RegexPatternMatcher(wildcardToRegex(pattern, cache))
                }
                return matcher to include
            }
            return null to include
        }

        companion object {
            fun wildcardToRegex(wildcard: String, cache: PatternBuilder.Cache): Regex {
                val regex = StringBuilder()
                for (c in wildcard.toCharArray()) {
                    when (c) {
                        '.' -> regex.append("\\.")
                        '*' -> regex.append(".*")
                        '?' -> regex.append(".")
                        else -> regex.append(Regex.escape(c.toString()))
                    }
                }

                return cache.getOrCompute("^$regex$")
            }
        }
    }

    class RegexStr(override val value: String) : PatternHolder<RegexStr>(), WithRawString {
        override fun normalize() = RegexStr(value.trim())

        override fun branchRegexMatcher(cache: PatternBuilder.Cache): Pair<PatternBuilder.PatternMatcher?, Boolean> {
            val (pattern, include) = value.parseBranchSpecLine()
            if (pattern != null) {
                val matcher = pattern.wrapCompileErrors(true) {
                    PatternBuilder.RegexPatternMatcher(cache.getOrCompute(pattern))
                }
                return matcher to include
            }
            return null to include
        }
    }

    class RegexObj(val value: Regex, val include: Boolean) : PatternHolder<RegexObj>() {
        override fun normalize() = this

        override fun branchRegexMatcher(cache: PatternBuilder.Cache): Pair<PatternBuilder.PatternMatcher?, Boolean> =
            PatternBuilder.RegexPatternMatcher(value) to include
    }

    private data class BranchSpecLine(val ref: String?, val include: Boolean)

    companion object {
        // Wildcard pattern, which optionally ends with asterisk
        private val simpleWildcardPattern = Regex("^[^\\?\\*]+\\*?$")

        private fun String.wrapCompileErrors(
            isRegex: Boolean,
            action: () -> PatternBuilder.PatternMatcher
        ): PatternBuilder.PatternMatcher {
            return try {
                action()
            } catch (ex: Throwable) {
                LOGGER.log(Level.WARNING, "Cannot parse pattern '$this', isRegex=$isRegex", ex)
                PatternBuilder.FALSE
            }
        }

        private fun String.parseBranchSpecLine(): BranchSpecLine = when {
            isEmpty() -> BranchSpecLine(null, true)
            startsWith(PatternBuilder.commentPrefix) -> BranchSpecLine(null, true) // comment
            startsWith(PatternBuilder.excludePrefix) -> BranchSpecLine(substring(2).trim(), false)
            startsWith("^") -> BranchSpecLine(substring(1).trim(), false)
            startsWith("-") -> BranchSpecLine(substring(1).trim(), false)
            startsWith(PatternBuilder.includePrefix) -> BranchSpecLine(substring(2).trim(), true)
            startsWith("+") -> BranchSpecLine(substring(1).trim(), true)
            else -> BranchSpecLine(this, true) //default is "+:" pattern
        }
    }
}

private val LOGGER = Logger.getLogger(BranchPattern::class.java.name)
