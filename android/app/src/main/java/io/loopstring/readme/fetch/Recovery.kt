package io.loopstring.readme.fetch

import android.content.Context
import androidx.work.WorkManager
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store

/**
 * R-M02 recovery, run at every process start: a fetching item whose work is gone (finished or
 * never recorded) can never leave fetching on its own, so it becomes fetch-failed: interrupted.
 * Work still queued or running is left alone (WorkManager re-runs it). Fetched items wait for
 * JS extraction (ADR 0007) and are not touched.
 */
object Recovery {
  fun run(context: Context) {
    val store = Store.get(context)
    val work = WorkManager.getInstance(context)
    for (id in store.idsInState(States.FETCHING)) {
      val alive = work.getWorkInfosForUniqueWork(FetchWorker.uniqueName(id)).get()
        .any { !it.state.isFinished }
      if (!alive) store.fetchFailed(id, "interrupted")
    }
  }
}
