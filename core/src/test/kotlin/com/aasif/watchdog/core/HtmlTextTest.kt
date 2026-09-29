package com.aasif.watchdog.core

import com.aasif.watchdog.core.parser.HtmlText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlTextTest {

    @Test
    fun `tags become spaces so adjacent cells do not run together`() {
        assertEquals(
            "Netflix Rs 649.00",
            HtmlText.toPlainText("<table><tr><td>Netflix</td><td>Rs 649.00</td></tr></table>"),
        )
    }

    @Test
    fun `script and style contents are dropped, not flattened into the text`() {
        val text = HtmlText.toPlainText(
            "<style>.total{color:#20B9}</style><p>Paid Rs 199</p><script>var amount = 9999;</script>"
        )
        assertEquals("Paid Rs 199", text)
    }

    @Test
    fun `comments are dropped`() {
        assertEquals("Paid Rs 199", HtmlText.toPlainText("<p>Paid <!-- tracking id 4242 -->Rs 199</p>"))
    }

    @Test
    fun `numeric entities decode so the rupee sign survives for amount extraction`() {
        assertEquals("₹649.00", HtmlText.toPlainText("<td>&#8377;649.00</td>"))
        assertEquals("₹649.00", HtmlText.toPlainText("<td>&#x20B9;649.00</td>"))
    }

    @Test
    fun `named entities decode`() {
        assertEquals("Tom & Jerry \"plan\" <b>", HtmlText.toPlainText("Tom &amp; Jerry &quot;plan&quot; &lt;b&gt;"))
    }

    @Test
    fun `non-breaking spaces collapse like ordinary whitespace`() {
        assertEquals("Rs 199 charged", HtmlText.toPlainText("Rs&nbsp;199\n\n\tcharged"))
    }

    @Test
    fun `malformed entities do not crash and do not leave digits behind`() {
        val text = HtmlText.toPlainText("<p>&#99999999999; &#xZZZZ; &unknown; Rs 199</p>")
        assertTrue(text.endsWith("Rs 199"), text)
        assertFalse(text.contains("99999999999"), text)
    }

    @Test
    fun `plain text passes through unchanged apart from whitespace`() {
        assertEquals(
            "Rs.199.00 will be debited towards Spotify AutoPay mandate",
            HtmlText.toPlainText("Rs.199.00 will be debited towards Spotify AutoPay mandate"),
        )
    }
}
