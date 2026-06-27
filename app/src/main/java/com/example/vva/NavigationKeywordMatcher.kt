package com.example.vva

import java.util.Locale

/** Utility to detect navigation intents and extract destinations from speech text. */
object NavigationKeywordMatcher {

    data class MatchResult(
            val isNavigation: Boolean,
            val isStop: Boolean = false,
            val isStopSpeaking: Boolean = false,
            val target: String?
    )

    private val triggerKeywords = listOf("室内导航", "导航", "带路", "带我去", "前往", "去", "到")
    private val stopKeywords = listOf("停止导航", "取消导航", "结束导航", "不用导航了", "别导航了")
    // 「停止说话」：立即静音所有播放（LLM 对话 + 导航 TTS），不影响导航状态。
    // 注意：必须排在 stopKeywords（停止导航）之前判断，且关键词要够特异，
    // 避免和「停止导航」混淆。
    private val stopSpeakingKeywords = listOf(
        "停止说话", "别说了", "闭嘴", "安静", "不要再说了", "停止播放", "停止语音"
    )

    // Match patterns:
    // 1. "室内导航[到/去]XXX"
    // 2. "帮我[导航/带路]到XXX"
    // 3. "我想去XXX"
    private val targetRegex =
            Regex("(?:室内导航|导航|带路|带我去|前往|去|到)(?:到|去|到)?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,32})")

    fun match(asrText: String): MatchResult {
        val text = asrText.trim()
        if (text.isEmpty()) return MatchResult(
            isNavigation = false, isStop = false, isStopSpeaking = false, target = null
        )

        val normalized = text.lowercase(Locale.getDefault())

        // 1. 「停止说话」优先级最高：立即静音所有播放，不碰导航状态。
        //    （注意：必须在 stopKeywords 之前判断，因为 stopKeywords 里没有
        //    “停止说话”这类词，但语义上要先拦截「让 AI 闭嘴」。）
        if (stopSpeakingKeywords.any { normalized.contains(it) }) {
            return MatchResult(isNavigation = false, isStop = false, isStopSpeaking = true, target = null)
        }

        // 2. 停止导航
        if (stopKeywords.any { normalized.contains(it) }) {
            return MatchResult(isNavigation = false, isStop = true, isStopSpeaking = false, target = null)
        }

        // 2. Extract the specific target destination
        val target =
                targetRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf {
                    it.isNotEmpty()
                }

        // We only consider it a full navigation request if a target is found.
        return MatchResult(
            isNavigation = target != null,
            isStop = false,
            isStopSpeaking = false,
            target = target
        )
    }
}
