package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.chat.ComposerMode
import com.offlineagent.core.runtime.chat.FollowUp
import com.offlineagent.core.runtime.chat.ReadAheadFooter
import com.offlineagent.core.runtime.chat.ReadAheadRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.2.3: only the plain chat route is read ahead; tool / agent / screen / export routes never are. */
class ReadAheadRouteTest {
    private fun route(text: String, mode: ComposerMode = ComposerMode.CHAT, fu: FollowUp.Kind = FollowUp.Kind.NONE,
                      screen: Boolean = false, agents: Boolean = false, canvasInChat: Boolean = false) =
        ReadAheadRoute.isPlainChat(text, mode, fu, screen, agents, canvasInChat)

    @Test fun plainChatOnly() {
        assertTrue(route("what are transformers"))
        assertTrue(route("summary of our previous chats", ComposerMode.CANVAS, canvasInChat = true))
        assertFalse("real canvas", route("make a table of my plan", ComposerMode.CANVAS))
        assertFalse("agents take task-like asks", route("set an alarm for 6", agents = true))
        assertFalse("screen agent", route("open whatsapp and type hi", screen = true))
        assertFalse("skill", route("/write-report on GPUs"))
        assertFalse("pdf export follow-up", route("make it as a pdf", fu = FollowUp.Kind.EXPORT))
        for (m in listOf(ComposerMode.AGENT, ComposerMode.PHONE, ComposerMode.AUTOMATION, ComposerMode.PDF_REPORT)) assertFalse(m.name, route("do it", m))
        assertFalse(route("   "))
    }

    @Test fun footer() {
        assertEquals("prefilled while typing: 512 tok · rest 9 tok in 0.4 s", ReadAheadFooter.line(512, 9, 400))
        assertEquals("prefilled while typing: 0 tok · rest 30 tok in 1.2 s · reasoned ahead: 180 tok", ReadAheadFooter.line(0, 30, 1200, 180))
        assertEquals("", ReadAheadFooter.line(0, 600, 9000))
    }
}
