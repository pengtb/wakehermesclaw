package com.openclaw.assistant.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 发短信**并等结果**。
 *
 * 原来的实现是 `SmsManager.getDefault().sendTextMessage(...)` 然后立刻回 `sent: true` ——
 * 那个 true 只代表"调用没抛异常"。短信没发出去（无服务、SIM 不对、被运营商拦）时一样是 true，
 * 桥这侧完全看不出来。这里改成：带 sentIntent 发送，等回调拿 resultCode，
 * 结果码映射成可读原因再返回。
 */
object SmsSender {

    private const val ACTION_SMS_SENT = "com.openclaw.assistant.bridge.SMS_SENT"
    private const val EXTRA_TOKEN = "token"

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    @Volatile
    private var receiverRegistered = false

    data class Outcome(val sent: Boolean, val resultCode: Int?, val reason: String?)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SMS_SENT) return
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
            val code = resultCode
            waiters.remove(token)?.complete(code)
        }
    }

    private fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(ACTION_SMS_SENT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    suspend fun send(context: Context, number: String, text: String, timeoutMs: Long = 25_000L): Outcome {
        ensureReceiver(context)

        val token = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Int>()
        waiters[token] = deferred

        val sentIntent = PendingIntent.getBroadcast(
            context.applicationContext,
            token.hashCode(),
            Intent(ACTION_SMS_SENT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val manager = try {
            smsManager(context)
        } catch (e: Exception) {
            waiters.remove(token)
            return Outcome(false, null, "no SmsManager: ${e.message ?: e.javaClass.simpleName}")
        }

        try {
            manager.sendTextMessage(number, null, text, sentIntent, null)
        } catch (e: SecurityException) {
            waiters.remove(token)
            return Outcome(false, null, "permission denied")
        } catch (e: Exception) {
            waiters.remove(token)
            return Outcome(false, null, e.message ?: e.javaClass.simpleName)
        }

        // 真正的结果在广播里：等它，超时算失败
        val code = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (code == null) {
            waiters.remove(token)
            return Outcome(false, null, "no sent callback within ${timeoutMs / 1000}s")
        }
        return if (code == android.app.Activity.RESULT_OK) {
            Outcome(true, code, null)
        } else {
            Outcome(false, code, describe(code))
        }
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        else -> "error code $code"
    }
}
