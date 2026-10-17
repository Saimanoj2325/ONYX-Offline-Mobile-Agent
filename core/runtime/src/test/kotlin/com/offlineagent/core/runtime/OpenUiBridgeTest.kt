package com.offlineagent.core.runtime

import com.offlineagent.core.openui.OpenUiParser
import com.offlineagent.core.openui.OpenUiResolver
import com.offlineagent.core.runtime.ui.OpenUiBridge
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiBridgeTest {
    @Test fun legacyCanvasJsonRendersThroughOpenUi() {
        val json = """{"title":"Python vs \"Java\"","blocks":[
          {"type":"table","title":"Basics","columns":["Lang","Typing"],"rows":[["Python","dynamic"],["Java","static"]]},
          {"type":"chart","kind":"bar","title":"Speed","labels":["Py","Java"],"series":[{"name":"score","values":[3,8]}]},
          {"type":"chart","kind":"pie","title":"Share","labels":["a","b"],"series":[{"name":"s","values":[40,60]}]},
          {"type":"callout","tone":"warning","title":"Note","text":"Line1\nLine2"},
          {"type":"checklist","title":"Todo","items":[{"text":"Install","done":true},{"text":"Practice","done":false}]},
          {"type":"steps","title":"Plan","items":[{"title":"Day 1","detail":"Basics"}]},
          {"type":"actions","buttons":[{"label":"More","prompt":"Tell me more"}]}
        ]}"""
        val doc = (UiParser.parse(json) as UiParseResult.Success).doc
        val src = OpenUiBridge.fromUiDoc(doc)
        val r = OpenUiResolver().resolve(OpenUiParser.parse(src))
        assertTrue(r.errors.filter { !it.warning }.toString(), r.errors.none { !it.warning })
        val types = r.root!!.walk().map { it.type }.toList()
        assertTrue(types.containsAll(listOf("Table", "BarChart", "PieChart", "Callout", "CheckBoxGroup", "Steps", "FollowUpBlock", "CardHeader")))
        assertEquals("Python vs \"Java\"", r.root!!.nodes("children")[0].str("text"))
        assertEquals(OpenUiBridge.toOpenUiSource(json), src)
        assertEquals(src, OpenUiBridge.toOpenUiSource(OpenUiBridge.wrap(src)))
    }
}
