package io.loopstring.readme.fetch

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.util.concurrent.TimeUnit

/**
 * R-M02/R-M03: the request runs in WorkManager, so it survives the share activity finishing.
 * One run per user request: a failure is stored for the user's Retry and never retried
 * automatically. If the process dies mid-request WorkManager runs it again, which is the same
 * request (its first GET never completed). No network constraint: an offline share fails at
 * once as "offline", visible, instead of waiting.
 */
class FetchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    val id = inputData.getLong(KEY_ID, -1)
    val store = Store.get(applicationContext)
    val item = store.item(id) ?: return Result.success()
    val url = item.url
    if (item.state != States.FETCHING || url == null) return Result.success()
    val started = System.nanoTime()
    val outcome = try {
      when (val r = Fetcher().fetch(url)) {
        is FetchResult.Ok -> {
          store.fetchSucceeded(id, r.html)
          "ok"
        }
        is FetchResult.Failed -> {
          store.fetchFailed(id, r.reason)
          r.reason
        }
      }
    } catch (t: Throwable) {
      // Disk full, a failed rename, a SQLite error: without this the item would sit in fetching
      // until the next process start. The class name only (AGENTS.md 1).
      runCatching { store.fetchFailed(id, "interrupted") }
      "interrupted:${t.javaClass.simpleName}"
    }
    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    // AGENTS.md 1: an id, a reason class and a duration; never the URL.
    Log.i(TAG, "request id=$id outcome=$outcome ms=$ms")
    return Result.success()
  }

  companion object {
    const val KEY_ID = "itemId"
    private const val TAG = "ReadMe"

    fun uniqueName(id: Long) = "fetch-$id"

    fun enqueue(context: Context, id: Long) {
      WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueName(id),
        // KEEP: a second enqueue while one is pending is the same request.
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<FetchWorker>().setInputData(workDataOf(KEY_ID to id)).build(),
      )
    }
  }
}
