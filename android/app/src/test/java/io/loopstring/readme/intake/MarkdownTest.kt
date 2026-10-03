package io.loopstring.readme.intake

import io.loopstring.readme.store.ParagraphRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownTest {
  private fun rows(md: String) = Markdown.toNote(md).paragraphs
  private fun p(t: String) = ParagraphRow("p", t)
  private fun h(t: String) = ParagraphRow("heading", t)
  private fun li(t: String) = ParagraphRow("li", t)

  @Test fun frontMatterIsDropped() {
    val md = "---\ntags: [a, b]\naliases: x\n---\n# Title\n\nBody text."
    assertEquals(listOf(h("Title"), p("Body text.")), rows(md))
  }

  @Test fun aRuleAtTheTopIsNotMistakenForFrontMatterWithoutAClose() {
    assertEquals(listOf(p("Only a paragraph.")), rows("---\nOnly a paragraph."))
  }

  @Test fun headingsAtxAndSetextBecomeHeadingsAndTitleIsTheFirst() {
    val note = Markdown.toNote("Intro line.\n\n## Second ##\n\nThird\n=====\n\nText.")
    assertEquals(listOf(p("Intro line."), h("Second"), h("Third"), p("Text.")), note.paragraphs)
    assertEquals("Second", note.title)
  }

  @Test fun aNoteWithoutHeadingsHasNoTitle() {
    assertNull(Markdown.toNote("Just text.").title)
  }

  @Test fun linesOfAParagraphAreJoinedAndBlankLinesSplit() {
    assertEquals(listOf(p("one two"), p("three")), rows("one\ntwo\n\nthree"))
  }

  @Test fun listItemsAndTasksAreItemsWithoutMarkers() {
    val md = "- first\n* second\n+ third\n1. fourth\n2) fifth\n- [ ] todo\n- [x] done"
    assertEquals(
      listOf(li("first"), li("second"), li("third"), li("fourth"), li("fifth"), li("todo"), li("done")),
      rows(md),
    )
  }

  @Test fun anIndentedContinuationJoinsItsItem() {
    assertEquals(listOf(li("first and more"), p("After.")), rows("- first\n  and more\n\nAfter."))
  }

  @Test fun codeFencesAreSkippedEntirely() {
    val md = "Before.\n\n```kotlin\nval x = 1 // # not a heading\n```\n\nAfter.\n\n~~~\nmore code\n~~~"
    assertEquals(listOf(p("Before."), p("After.")), rows(md))
  }

  @Test fun anUnclosedFenceSkipsTheRest() {
    assertEquals(listOf(p("Before.")), rows("Before.\n\n```\ncode\nstill code"))
  }

  @Test fun quotesAndCalloutsAreReadWithoutMarkers() {
    assertEquals(listOf(p("Quoted words.")), rows("> Quoted\n> words."))
    assertEquals(listOf(p("Watch out here.")), rows("> [!warning] Watch out\n> here."))
  }

  @Test fun rulesAndCommentsAreDropped() {
    val md = "One.\n\n***\n\nTwo. %%hidden%%\n\n<!-- also hidden -->\n\n___\n\nThree."
    assertEquals(listOf(p("One."), p("Two."), p("Three.")), rows(md))
  }

  @Test fun tablesAreReadRowByRow() {
    val md = "| Name | Size |\n| --- | :-: |\n| Apple | Big |"
    assertEquals(listOf(p("Name, Size"), p("Apple, Big")), rows(md))
  }

  @Test fun linksReadAsTheirText() {
    assertEquals("see the docs now", Markdown.inline("see [the docs](https://example.com/a?b=c) now"))
    assertEquals("see Docs", Markdown.inline("see [Docs][ref]"))
    assertEquals("go https://example.com", Markdown.inline("go <https://example.com>"))
  }

  @Test fun wikilinksReadAsTheTargetOrTheAlias() {
    assertEquals("see Some Note", Markdown.inline("see [[Some Note]]"))
    assertEquals("see this one", Markdown.inline("see [[Some Note|this one]]"))
    assertEquals("see Some Note Part", Markdown.inline("see [[Some Note#Part]]"))
    assertEquals("see Some Note", Markdown.inline("see [[Some Note#^abc123]]"))
  }

  @Test fun embedsAndImagesAreDropped() {
    assertEquals("before after", Markdown.inline("before ![[diagram.png]] ![alt](pic.jpg) after"))
  }

  @Test fun emphasisCodeStrikeAndHighlightLoseTheirMarkers() {
    assertEquals(
      "bold strong italic also gone marked code",
      Markdown.inline("**bold** __strong__ *italic* _also_ ~~gone~~ ==marked== `code`"),
    )
    assertEquals("nested bold italic", Markdown.inline("***nested bold italic***"))
  }

  @Test fun underscoresInsideWordsStay() {
    assertEquals("snake_case_name stays", Markdown.inline("snake_case_name stays"))
  }

  @Test fun aLoneAsteriskStays() {
    assertEquals("5 * 3 = 15", Markdown.inline("5 * 3 = 15"))
  }

  @Test fun tagsFootnotesBlockIdsAndEscapesAreCleaned() {
    assertEquals("about project/alpha here", Markdown.inline("about #project/alpha here[^1] ^block-1"))
    assertEquals("C# and a #1 fan", Markdown.inline("C# and a #1 fan"))
    assertEquals("*literal* stars", Markdown.inline("\\*literal\\* stars"))
  }

  @Test fun htmlTagsAreDroppedAndTheirTextKept() {
    assertEquals("a b", Markdown.inline("a<br>b"))
    assertEquals("red text", Markdown.inline("<span style=\"x\">red</span> text"))
  }

  @Test fun nothingSpokenIsLeftAsMarkup() {
    val md = "# H\n\n**Bold** with [link](u) and [[Wiki|w]].\n\n- *item*\n\n> quote"
    for (r in rows(md)) {
      assertFalse(r.text, Regex("[#*\\[\\]>|]").containsMatchIn(r.text))
    }
  }

  @Test fun crlfAndABomAreHandled() {
    assertEquals(listOf(h("T"), p("a b")), rows("﻿# T\r\n\r\na\r\nb"))
  }
}
