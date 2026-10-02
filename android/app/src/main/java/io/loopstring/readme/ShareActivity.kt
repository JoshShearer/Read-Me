package io.loopstring.readme

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.intake.Intake
import io.loopstring.readme.store.Store

/**
 * R-M02: the share target. Classifies and stores the item, queues the request for a link, and
 * finishes at once so the user stays in the app they shared from. No JS runtime is started.
 */
class ShareActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val shared = intent
      ?.takeIf { it.action == Intent.ACTION_SEND }
      ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
      ?.toString()
      .orEmpty()
    val store = Store.get(this)
    val now = System.currentTimeMillis()
    val saved = when (val share = Intake.classify(shared)) {
      is Intake.Share.Link -> {
        val id = store.insertLink(share.url, Intake.hostTitle(share.url), now)
        FetchWorker.enqueue(this, id)
        true
      }
      is Intake.Share.Text -> {
        val paragraphs = Intake.splitParagraphs(share.text)
        store.insertText(Intake.textTitle(paragraphs), paragraphs, now)
        true
      }
      Intake.Share.Empty -> false
    }
    Toast.makeText(
      this,
      if (saved) "Saved to Read Me" else "Nothing to save",
      Toast.LENGTH_SHORT,
    ).show()
    finish()
  }
}
