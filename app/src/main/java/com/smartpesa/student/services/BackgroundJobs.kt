package com.smartpesa.student.services

import android.content.Context
import com.smartpesa.student.data.FinanceDatabase
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** No message body goes into WorkManager's own (unencrypted) job database. */
object BackgroundJobs {
    fun alerts(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("alert-delivery", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AlertWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
    }
    fun messages(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("message-import", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MessageWorker>().setInputData(workDataOf("generation" to context.getSharedPreferences("settings", 0).getLong("generation", 0))).build())
    }
}
class AlertWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        NotificationDelivery.dispatch(applicationContext, FinanceDatabase.get(applicationContext).financeDao())
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { Result.retry() }
}
class MessageWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        MessageImport.run(applicationContext, inputData.getLong("generation", -1))
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: SecurityException) { Result.success() } catch (_: Exception) { Result.retry() }
}
