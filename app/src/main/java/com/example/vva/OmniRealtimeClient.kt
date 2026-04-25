package com.example.vva

import android.util.Base64
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.google.gson.JsonObject
import timber.log.Timber


class OmniRealtimeClient(
    private val apiKey: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onResponseText: (String) -> Unit,
    private val onResponseAudio: (ByteArray) -> Unit,
    private val onResponseDone: () -> Unit,
    private val onAsrResult: (String) -> Unit,
    private val onVadBegin: () -> Unit,
    private val onVadEnd: () -> Unit,
) {

    private var conversation: OmniRealtimeConversation? = null


    @Volatile
    private var isConnecting = false

    @Volatile
    private var isConnected = false

    private val baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    private val model = "qwen3-omni-flash-realtime"
    private val voiceId = "Cherry"
//    private val systemPrompt = """
//Role:
//  - 你是一位实时多模态视觉与语音感知专家，完全无法依靠视觉，因此你需要根据用户的语音输入和图片将他们所处场景中的视觉信息转换为清晰、生动的语音描述。
//Background:
//  - 你以当前画面图像与用户语音输入为感知基础，辅助用户完成场景空间理解、障碍识别、文字读取、交互确认等任务。
//  - 用户失明，需依赖你来描述他们无法看见的世界，将“不可见”转化为“可听、可感”的场景信息。
//Constraints:
//  - 严格禁止使用“图像中”“照片中”“视频中”等表达
//  - 听到用户说话，再作出回应，不要主动说话
//"""

    private val systemPrompt = """
【角色】  
你是一套运行在盲人辅助智能眼镜上的实时多模态语音助手。

【背景】  
盲人佩戴的智能眼镜具有摄像头与麦克风：用户语音被实时采集并转成文字，摄像头拍摄当前室内场景（如会议室、办公室、走廊等）。  
用户完全看不见画面，只能通过你的语音输出来理解当前环境。  
系统重点能力包括：视觉理解、场景描述、物体识别、物体寻找、人物识别、文本读取等。

【目的】  
你需要根据“用户语音内容 + 当前场景图片”，提供：  
1. 清晰、准确、智能的场景感知描述；  
2. 对物体、设备、家具、标识、文本等的识别与理解；  
3. 在用户主动询问物品时，给出目标物的定位说明；  
4. 对人物数量、衣着、表情、姿态、位置、动作进行中性的描述；  
5. 让盲人用户能够轻松听懂当前场景，感受到眼镜具备强大的视觉分析能力。

【风格】  
1. 表达风格：清晰、自然、准确、口语化。  
2. 句子短、结构简单，适合盲人听觉理解。  
3. 使用【方位 + 距离】进行定位，例如“你前方”、“左前方”“右前方”。  
4. 人物描述包含：人数、衣着颜色、表情、姿态、动作、相对位置。  
5. 不使用模糊指代词，如“这里”“那边”“这个地方”等。

【输出】  
1. 默认输出 2–5 句短句，优先保证可理解性和简洁性。  
2. 根据用户意图自动选择内容：  
   - 场景描述：房间类型、桌椅布局、屏幕、门窗方向、物品分布；  
   - 物品识别：如电脑、手机、水杯、背包、文档、摄像头等；  
   - 物体查找：给出物品的方位、距离、显著特征；  
   - 人物描述：人数、位置、动作、衣着、表情；  
   - 文本读取：屏幕文字、标语、纸张内容等。  
3. 若画面不清晰或无法确定，应明确表达不确定性：  
   - “这部分画面比较模糊，我可能看不清楚。”  
--------------------------------------------
【情景示例】（用于指导模型在不同任务下的输出方式）
以下示例仅用于说明输出风格和结构，不是固定答案。

示例 1：场景分析 / 场景描述  
用户输入：  
“帮我分析一下当前场景。”

模型理想输出示例：  
“你现在在一个会议室里。你前方有一张长桌，桌上放着几台笔记本电脑和水杯。  
右侧靠墙的位置有一块大屏幕。  
左侧有两把空椅子。  
房间光线明亮，整体比较整洁。”

特点说明：  
- 输出较为详细  
- 覆盖房间类型、重要物体分布、方位关系、环境状态  
- 适合盲人在脑中构建空间地图  

示例 2：物体寻找 / 物品定位  
用户输入：  
“帮我找一下水杯在哪里？”

模型理想输出示例：  
“你的水杯在你的右前方。  
它在一台灰色笔记本电脑的右侧，杯身是透明的。  
桌面上没有明显遮挡，你可以轻松够到它。”

特点说明：  
- 重点突出目标物体  
- 使用“方位 + 距离 + 参照物”  

- 只关注与寻找任务直接相关的信息  
示例 3：人物感知  
用户输入：  
“我前面里有人吗？”

模型理想输出示例：  
“有两个人在你前方。  
左边那位穿黑色外套，正坐着看向屏幕。  
右边那位穿白色衬衫，表情自然，正在整理桌面。”

特点说明：  
- 人数 + 方位 + 衣着 + 动作  
- 不进行身份或情绪的主观推断  

【总结】  
你需要根据用户意图选择合适的输出方式：  
- 若用户想了解场景 → 输出较详细的场景描述；  
- 若用户想找物品 → 专注于“方位 + 模糊距离 + 参照物”；  
- 若用户问人物 → 描述人数、衣着、动作、位置；  
- 若用户问文本 → 读取最重要的文字内容。  
- **【核心禁令】若用户请求“导航”相关功能（如“开始导航”、“停止导航”、“去某个地方”等），你必须只回复：好的，开始/停止 导航，然后保持绝对沉默，不要回复任何语音或文字，也不要进行确认，因为系统会自动拦截并由专用导航模块处理。**

所有输出均需口语化、易理解，并适合盲人听觉体验。
    """.trimIndent()

    fun connect() {

        if (isConnecting) {
            Timber.i("already connecting")
            return
        }

        if (isConnected) {
            Timber.i("already connected")
            return
        }

        isConnecting = true

        val param: OmniRealtimeParam = OmniRealtimeParam.builder()
            .model(model)
            .apikey(apiKey)
            .url(baseUrl)
            .build()

        conversation = OmniRealtimeConversation(param, object : OmniRealtimeCallback() {

            override fun onOpen() {
                Timber.i("Connected Successfully")
                isConnected = true
                isConnecting = false
                onConnected()
            }

            override fun onEvent(message: JsonObject?) {
                if (message != null) {
                    handleEvent(message)
                } else {
                    Timber.w("message is null")
                }
            }

            override fun onClose(code: Int, reason: String?) {
                Timber.i("connection closed code: $code, reason: $reason")
                isConnected = false
                isConnecting = false
                onDisconnected()
            }
        })

        conversation?.connect()

        conversation?.updateSession(
            OmniRealtimeConfig.builder()
                .modalities(
                    listOf(
                        OmniRealtimeModality.AUDIO,
                        OmniRealtimeModality.TEXT
                    )
                )
                .voice(voiceId)
                .enableTurnDetection(true)
                .turnDetectionType("server_vad")
                .turnDetectionThreshold(0.5f)
                .prefixPaddingMs(500)
                .turnDetectionSilenceDurationMs(900)
                .enableInputAudioTranscription(true)
                .parameters(mapOf("instructions" to systemPrompt))
                .build()
        )
    }

    fun disconnect(reason: String? = null) {
        conversation?.close(1000, reason ?: "bye")
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isConnected) {
            return
        }

        val b64 = Base64.encodeToString(audioData, Base64.NO_WRAP)

        Timber.v("send audio %.1f KB", b64.length / 1024f)
        conversation?.appendAudio(b64)
    }

    fun sendImage(bytes: ByteArray) {
        if (!isConnected) {
            return
        }

        // 计算 Base64 编码前的最大允许大小
        // Base64 会增加约 33% 的大小，所以我们需要更严格的限制
        val maxRawSize = (262144 * 0.75).toInt() // 大约 196,608 字节

        val compressed = ImageCompressor.compressImageWithResize(
            bytes = bytes,
            maxSize = maxRawSize,
            maxWidth = 640,  // 可根据需求调整
            maxHeight = 640,
            minQuality = 30,
            keepAspectRatio = true
        )

//        val compressed = imageManager.compressImageToTargetSize(
//            bytes,
//            maxRawSize,
//            1
//        )

        val b64 = Base64.encodeToString(compressed, Base64.NO_WRAP)

        if (b64.length >= 262144) {
            Timber.w("[WS] Failed to send image too large, %.1f KB ", b64.length / 1024f)
            return
        }

        Timber.v("send image %.1f KB", b64.length / 1024f)
        conversation?.appendVideo(b64)
    }

    fun clearAudioBuffer() {
        if (!isConnected) {
            return
        }
        // Timber.i("clearAudioBuffer")
        // conversation?.clearAppendedAudio()
    }

    private fun handleEvent(event: JsonObject) {
        val type = event.get("type").asString
        Timber.v("event type: $type")
        when (type) {
            "input_audio_buffer.speech_started" -> {
                Timber.i("[用户开始说话]")
                onVadBegin()
            }

            "input_audio_buffer.speech_stopped" -> {
                Timber.i("[用户停止说话]")
                onVadEnd()
            }

            "response.audio.delta" -> {
                val base64Audio = event.get("delta").asString
                val audio: ByteArray? = Base64.decode(base64Audio, Base64.NO_WRAP)
                if (audio != null) {
                    onResponseAudio(audio)
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val text = event.get("transcript").asString
                Timber.d("用户: %s", event.get("transcript").asString)
                onAsrResult(text)
            }

            "response.audio_transcript.delta" -> {
                val text = event.get("delta").asString
                Timber.v("AI-delta: %s", event.get("delta").asString)
                onResponseText(text)
            }

            "response.audio_transcript.done" -> {
                Timber.d("AI: %s", event.get("transcript").asString)
            }

            "response.done" -> {
                Timber.d("回复完成")
                onResponseDone()
            }

            "error" -> {
                Timber.e("error: %s", event)
            }
        }
    }
}