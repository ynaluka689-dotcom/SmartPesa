package com.smartpesa.student.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.smartpesa.student.data.FinanceDatabase
import com.smartpesa.student.domain.MpesaParser
import kotlinx.coroutines.*

/** Android assembles message parts for us; join every part before strict parsing. */
class MpesaReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
            !context.getSharedPreferences("settings", 0).getBoolean("messages", false)) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val payment = MpesaParser.parseParts(parts.map { it.originatingAddress.orEmpty() to it.messageBody.orEmpty() }) ?: return
        // Queue an inbox catch-up first; it survives process death during immediate delivery.
        val generation = context.getSharedPreferences("settings", 0).getLong("generation", 0)
        runCatching { BackgroundJobs.messages(context) }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8000) { LedgerService(context, FinanceDatabase.get(context).financeDao()).receive(payment, generation = generation) }
            } catch (_: Exception) { /* The persisted catch-up job can retry; never log private SMS. */ }
            finally { pending.finish() }
        }
    }
}
