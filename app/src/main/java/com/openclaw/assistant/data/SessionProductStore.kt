package com.openclaw.assistant.data

import android.content.Context
import com.openclaw.assistant.ChatProduct

/**
 * 会话属于哪个产品（OpenClaw / Hermes）。
 *
 * 为什么要单独存：聊天列表靠它决定每条会话标题旁边显示哪个标签。以前只有「手动新建会话」
 * 会写（[SessionListViewModel.saveProduct]），语音会话从来不写，于是读取时退回到一条启发式
 * 判断（"有 Hermes 且没配 OpenClaw HTTP 就算 Hermes，否则算 OpenClaw"）——只要本机同时还
 * 配了 OpenClaw HTTP，语音会话就一律被打成 OpenClaw，哪怕它实际走的是 Hermes。
 *
 * 现在语音链路自己把真实产品写进来（[com.openclaw.assistant.service.OpenClawSession]），
 * 这条启发式只作为老数据的兜底。
 */
object SessionProductStore {

    /** 与 SessionListViewModel 原来使用的文件名保持一致，老数据继续可读 */
    private const val PREFS = "chat_session_products"

    fun save(context: Context, sessionId: String, product: ChatProduct) {
        prefs(context).edit().putString(sessionId, product.name).apply()
    }

    fun load(context: Context, sessionId: String): ChatProduct? =
        prefs(context).getString(sessionId, null)
            ?.let { runCatching { ChatProduct.valueOf(it) }.getOrNull() }

    fun remove(context: Context, sessionId: String) {
        prefs(context).edit().remove(sessionId).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
