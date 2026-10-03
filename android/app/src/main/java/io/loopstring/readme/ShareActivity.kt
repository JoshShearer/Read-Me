package io.loopstring.readme

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.intake.Intake
import io.loopstring.readme.intake.SharedFile
import io.loopstring.readme.store.Store
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R-M02: the share target. Classifies and stores the item, queues the request for a link, and
 * finishes at once so the user stays in the app they shared from. No JS runtime is started.
 * R-C04: also opens a shared `.md` or `.txt` file as a text item.
 */
class ShareActivity : Activity() {
  private val main = Handler(Looper.getMainLooper())
  private val claimed = AtomicBoolean(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uri = sharedFile(intent)
    if (uri == null) {
      complete(saveText(intent))
      return
    }
    // A file is read off the main thread: a slow or hostile provider must not freeze the app it
    // was shared from. The activity stays (invisible) until then, which keeps the sender's grant.
    val type = intent?.type
    val timeout = Runnable { if (claimed.compareAndSet(false, true)) complete(UNREADABLE) }
    main.postDelayed(timeout, READ_TIMEOUT_MS)
    executor.execute {
      val message = saveFile(uri, type)
      main.post {
        main.removeCallbacks(timeout)
        if (message != null) complete(message)
      }
    }
  }

  private fun complete(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    finish()
  }

  /**
   * R-C04: Obsidian opens a note with ACTION_VIEW; other apps send it as EXTRA_STREAM. A SEND
   * that also carries text (a link with a thumbnail, say) is still R-M02's text share.
   */
  private fun sharedFile(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND ->
      if (intent.getCharSequenceExtra(Intent.EXTRA_TEXT).isNullOrBlank()) stream(intent) else null
    else -> null
  }?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }

  private fun stream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

  private fun saveText(intent: Intent?): String {
    val shared = intent
      ?.takeIf { it.action == Intent.ACTION_SEND }
      ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
      ?.toString()
      .orEmpty()
    val store = Store.get(this)
    val now = System.currentTimeMillis()
    return when (val share = Intake.classify(shared)) {
      is Intake.Share.Link -> {
        val id = store.insertLink(share.url, Intake.hostTitle(share.url), now)
        FetchWorker.enqueue(this, id)
        SAVED
      }
      is Intake.Share.Text -> {
        val paragraphs = Intake.splitParagraphs(share.text)
        store.insertText(Intake.textTitle(paragraphs), paragraphs, now)
        SAVED
      }
      Intake.Share.Empty -> NOTHING
    }
  }

  /** On the worker. Null when the timeout already answered, so nothing is stored late. */
  private fun saveFile(uri: Uri, type: String?): String? {
    val r = try {
      SharedFile.read(contentResolver, uri, type)
    } catch (_: Throwable) {
      SharedFile.Result.Unreadable
    }
    if (!claimed.compareAndSet(false, true)) return null
    return when (r) {
      is SharedFile.Result.Note -> try {
        Store.get(this).insertText(r.title, r.paragraphs, System.currentTimeMillis())
        SAVED
      } catch (_: Exception) {
        UNREADABLE
      }
      SharedFile.Result.Empty -> NOTHING
      SharedFile.Result.TooLarge -> TOO_LARGE
      SharedFile.Result.Unreadable -> UNREADABLE
    }
  }

  internal companion object {
    const val SAVED = "Saved to Read Me"
    const val NOTHING = "Nothing to save"
    const val TOO_LARGE = "That file is too large for Read Me (over 5 MB)"
    const val UNREADABLE = "Read Me could not read that file"
    const val READ_TIMEOUT_MS = 20_000L

    /** Where a shared file is read. Tests swap in a direct executor. */
    @Volatile var executor: Executor = Executors.newSingleThreadExecutor { r ->
      Thread(r, "share-file").apply { isDaemon = true }
    }
  }
}
