package io.loopstring.readme

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.intake.Intake
import io.loopstring.readme.intake.SharedFile
import io.loopstring.readme.store.Store

/**
 * R-M02: the share target. Classifies and stores the item, queues the request for a link, and
 * finishes at once so the user stays in the app they shared from. No JS runtime is started.
 * R-C04: also opens a shared `.md` or `.txt` file as a text item.
 */
class ShareActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val message = when (val uri = sharedFile(intent)) {
      null -> saveText(intent)
      else -> saveFile(uri, intent?.type)
    }
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    finish()
  }

  /** R-C04: Obsidian opens a note with ACTION_VIEW; other apps send it as EXTRA_STREAM. */
  private fun sharedFile(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> stream(intent)
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

  private fun saveFile(uri: Uri, type: String?): String =
    when (val r = SharedFile.read(contentResolver, uri, type)) {
      is SharedFile.Result.Note -> {
        Store.get(this).insertText(r.title, r.paragraphs, System.currentTimeMillis())
        SAVED
      }
      SharedFile.Result.Empty -> NOTHING
      SharedFile.Result.TooLarge -> "That file is too large for Read Me (over 5 MB)"
      SharedFile.Result.Unreadable -> "Read Me could not read that file"
    }

  private companion object {
    const val SAVED = "Saved to Read Me"
    const val NOTHING = "Nothing to save"
  }
}
