package com.smartpesa.student.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.smartpesa.student.data.FinanceDatabase
import com.smartpesa.student.domain.MpesaParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

object MessageImport {
    /** Explicit 90-day inbox import; unsupported messages never become guessed purchases. */
    suspend fun run(context: Context, generation: Long = context.getSharedPreferences("settings", 0).getLong("generation", 0)): Int = withContext(Dispatchers.IO) {
        if ((!context.getSharedPreferences("settings", 0).getBoolean("messages", false) || context.getSharedPreferences("settings", 0).getLong("generation", 0) != generation)) return@withContext 0
        val service = LedgerService(context, FinanceDatabase.get(context).financeDao())
        service.recoverLive(generation)
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext 0
        var count = 0
        val since = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(90)
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body"),
            "date >= ?", arrayOf(since.toString()), "date ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                if ((!context.getSharedPreferences("settings", 0).getBoolean("messages", false) || context.getSharedPreferences("settings", 0).getLong("generation", 0) != generation)) break
                if (MpesaParser.trustedSender(cursor.getString(0).orEmpty())) {
                    MpesaParser.parse(cursor.getString(1).orEmpty())?.let { if (service.ingest(it, historical = true, generation = generation)) count++ }
                }
            }
        }
        LedgerService.mutex.withLock {
            if (context.getSharedPreferences("settings", 0).getBoolean("messages", false) && context.getSharedPreferences("settings", 0).getLong("generation", 0) == generation) FinanceDatabase.get(context).financeDao().decideBudgets(System.currentTimeMillis())
        }
        runCatching { BackgroundJobs.alerts(context) }
        count
    }
}
