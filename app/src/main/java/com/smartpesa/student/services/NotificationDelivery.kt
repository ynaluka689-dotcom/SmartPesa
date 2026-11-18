package com.smartpesa.student.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.smartpesa.student.MainActivity
import com.smartpesa.student.R
import com.smartpesa.student.data.FinanceDao
import kotlinx.coroutines.sync.withLock

object NotificationDelivery {
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("receipts", "Transaction updates", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("budgets", "Monthly spending limits", NotificationManager.IMPORTANCE_DEFAULT))
    }
    suspend fun dispatch(context: Context, dao: FinanceDao) = LedgerService.mutex.withLock {
        channels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val preferences = context.getSharedPreferences("settings", 0)
        for (event in dao.pendingAlerts()) {
            val receipt = event.destination == "History"
            val permitted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (permitted && manager.areNotificationsEnabled() && preferences.getBoolean(if (receipt) "receipts" else "budget_alerts", true)) {
                val destination = Intent(context, MainActivity::class.java).putExtra("destination", event.destination)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(context, event.id.toInt(), destination, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, if (receipt) "receipts" else "budgets")
                    .setSmallIcon(R.drawable.brand_monochrome).setContentTitle(event.title).setContentText(event.body)
                    .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setOnlyAlertOnce(true).build()
                manager.notify(event.id.toInt(), notification)
            }
            // Disabled/denied alerts are consumed, avoiding a surprise flood when permission changes.
            dao.delivered(event.id)
        }
    }
}
