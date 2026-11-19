package io.github.lgpy.familycare.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionContentNeedTest {

    @Test
    fun `text may wrap onto two lines beside the action`() {
        // indent 52, text 400 wide on one line -> about 230 on two lines
        assertEquals(52 + 230, attentionContentNeed(natural = 452, longestWord = 150, indent = 52))
    }

    @Test
    fun `never narrower than the longest word`() {
        assertEquals(300, attentionContentNeed(natural = 352, longestWord = 300, indent = 52))
    }

    @Test
    fun `no text needs only the indent`() {
        assertEquals(52, attentionContentNeed(natural = 52, longestWord = 0, indent = 52))
    }
}
