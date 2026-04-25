package com.example.vva

import java.util.Locale

/** Utility to detect navigation intents and extract destinations from speech text. */
object NavigationKeywordMatcher {

    data class MatchResult(
            val isNavigation: Boolean,
            val isStop: Boolean = false,
            val target: String?
    )

    private val triggerKeywords = listOf("室内导航", "导航", "带路", "带我去", "前往", "去", "到")
    private val stopKeywords = listOf("停止导航", "取消导航", "结束导航", "不用导航了", "别导航了", "停止", "取消")

    // Match patterns:
    // 1. "室内导航[到/去]XXX"
    // 2. "帮我[导航/带路]到XXX"
    // 3. "我想去XXX"
    private val targetRegex =
            Regex("(?:室内导航|导航|带路|带我去|前往|去|到)(?:到|去|到)?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,32})")

    fun match(asrText: String): MatchResult {
        val text = asrText.trim()
        if (text.isEmpty()) return MatchResult(false, false, null)

        val normalized = text.lowercase(Locale.getDefault())

        // 1. Check for stop command first
        if (stopKeywords.any { normalized.contains(it) }) {
            return MatchResult(false, true, null)
        }

        // 2. Extract the specific target destination
        val target =
                targetRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf {
                    it.isNotEmpty()
                }

        // We only consider it a full navigation request if a target is found.
        return MatchResult(target != null, false, target)
    }
}
