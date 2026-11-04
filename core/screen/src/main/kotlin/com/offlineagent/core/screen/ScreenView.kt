package com.offlineagent.core.screen

import com.offlineagent.core.runtime.phone.ScreenElement
import com.offlineagent.core.runtime.phone.ScreenNode
import java.security.MessageDigest

/** Coarse role used in element lines and by screen_find(role=…). */
fun ScreenNode.role(): String = when {
    editable -> "input"
    checkable -> "checkbox"
    scrollable -> "list"
    clickable || longClickable -> "button"
    shortClass.contains("Image", true) -> "image"
    else -> "text"
}

/** A region of the screen (top-level container) for skeleton mode. Ref "@r2", nested "@r2.1". */
data class Region(val ref: String, val node: ScreenNode, val elements: List<ScreenElement>) {
    val kind: String get() {
        val id = node.viewId.substringAfterLast('/').lowercase()
        return when {
            node.scrollable || node.shortClass.contains("Recycler") || node.shortClass.contains("ListView") -> "list"
            Regex("toolbar|app_?bar|action_?bar|header|title").containsMatchIn(id) || node.shortClass.contains("Toolbar") -> "toolbar"
            Regex("bottom|nav|tab").containsMatchIn(id) || node.shortClass.contains("BottomNavigation") || node.shortClass.contains("TabLayout") -> "navigation"
            elements.any { it.node.editable } -> "form"
            else -> "group"
        }
    }
}

/**
 * One snapshot with snapshot-scoped refs. Element refs are "@e12" (bound to THIS snapshot id [id], "s3"); the long
 * form "s3:e12" is accepted too. [signature] identifies the screen's structure: when the live screen's signature
 * differs, every ref of this snapshot is STALE and the agent must re-snapshot (agent-desktop's STALE_REF rule).
 */
class ScreenView(val id: String, val frame: ScreenFrame) {
    val snapshot get() = frame.snapshot
    val pkg: String get() = frame.packageName
    val elements: List<ScreenElement> get() = snapshot.elements

    fun ref(e: ScreenElement) = "@e${e.id}"
    fun element(n: Int): ScreenElement? = snapshot.element(n)

    /** Structure hash: package, activity, and each element's class/role/id/path/label (input VALUES excluded). */
    val signature: String by lazy { signatureOf(frame) }

    // ------------------------------------------------------------------ regions (skeleton)
    val regions: List<Region> by lazy { regionsUnder(snapshot.root, "@r") }

    private fun inside(n: ScreenNode, e: ScreenElement) = e.node.path.size >= n.path.size && e.node.path.subList(0, n.path.size) == n.path

    private fun regionsUnder(start: ScreenNode, prefix: String): List<Region> {
        val within = elements.filter { inside(start, it) }
        var node = start
        // Unwrap single-child wrapper chains (FrameLayout > LinearLayout > …) until the tree branches.
        while (true) {
            val withEls = node.children.filter { c -> within.any { inside(c, it) } }
            if (withEls.size == 1 && within.none { it.node.path == node.path }) node = withEls.single() else break
        }
        val kids = node.children.filter { c -> within.any { inside(c, it) } }
        if (kids.size <= 1) return emptyList()
        return kids.mapIndexed { i, c -> Region("$prefix${if (prefix.endsWith("r")) "" else "."}${i + 1}", c, within.filter { inside(c, it) }) }
    }

    fun region(ref: String): Region? {
        val r = ref.trim().removePrefix("@").removePrefix("r")
        val parts = r.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        var list = regions
        var cur: Region? = null
        for ((depth, p) in parts.withIndex()) {
            cur = list.getOrNull(p - 1) ?: return null
            if (depth < parts.size - 1) list = regionsUnder(cur.node, cur.ref)
        }
        return cur
    }

    // ------------------------------------------------------------------ rendering
    fun line(e: ScreenElement, policy: ScreenSafetyPolicy? = null): String {
        val n = e.node
        val lbl = if (n.editable) listOf(n.hint, e.nearbyLabel, n.contentDescription, n.viewId.substringAfterLast('/')).firstOrNull { it.isNotBlank() } ?: "" else n.label
        return buildString {
            append(ref(e)).append(' ').append(n.role())
            if (lbl.isNotBlank()) append(" \"").append(ScreenData.label(lbl)).append('"')
            if (n.editable) append(" value=\"").append(if (n.password) (if (n.text.isNotEmpty()) "••••" else "") else ScreenData.label(n.text, 60)).append('"')
            if (n.checkable) append(if (n.checked) " [on]" else " [off]")
            if (n.password) append(" (password)")
            if (!n.enabled) append(" (disabled)")
            if (n.focused) append(" (focused)")
            if (n.selected) append(" (selected)")
            if ((n.clickable || n.longClickable) && (policy?.isIrreversible(n) ?: Irreversible.matches(n))) append(" (commits!)")
        }
    }

    private fun header(card: String?) = buildString {
        append("Screen ").append(id).append(" · ").append(snapshot.appLabel).append(" (").append(pkg).append(")")
        if (snapshot.activity.isNotBlank()) append(" · ").append(snapshot.activity.substringAfterLast('.'))
        append(" · ").append(elements.size).append(" elements\n")
        if (!card.isNullOrBlank()) append("App notes (your saved card for this app): ").append(card.take(400).replace('\n', ' ')).append('\n')
    }

    private fun summary(r: Region): String {
        val c = r.elements.groupingBy { it.node.role() }.eachCount()
        val counts = listOf("button", "input", "text", "checkbox", "list", "image").mapNotNull { k -> c[k]?.let { "$it $k${if (it > 1) "s" else ""}" } }.joinToString(", ")
        val sample = r.elements.map { it.node.label }.filter { it.isNotBlank() }.distinct().take(4).joinToString(", ") { "\"" + ScreenData.label(it, 30) + "\"" }
        return "${r.ref} ${r.kind} — $counts" + if (sample.isNotBlank()) " — e.g. $sample" else ""
    }

    /**
     * Skeleton: one line per region (kind, counts, sample labels); small regions (≤ [inlineMax] elements) are shown
     * in full. Keeps the prompt small for an 8K context on a 1.7B model; drill down with [drill].
     */
    fun skeleton(card: String? = null, maxChars: Int = 2400, inlineMax: Int = 4, policy: ScreenSafetyPolicy? = null): String {
        val body = buildString {
            if (regions.isEmpty()) { elements.forEach { appendLine(line(it, policy)) }; return@buildString }
            for (r in regions) {
                if (r.elements.size <= inlineMax) r.elements.forEach { appendLine(line(it, policy)) }
                else appendLine(summary(r))
            }
        }
        return ScreenData.wrap(pkg, header(card) + cap(body, maxChars, "drill into a region with root=@rN"))
    }

    /** Full element list of one region (nested regions summarised again if it is still too long). */
    fun drill(regionRef: String, card: String? = null, maxChars: Int = 2400, maxLines: Int = 40, policy: ScreenSafetyPolicy? = null): String? {
        val r = region(regionRef) ?: return null
        val body = buildString {
            appendLine("Region ${r.ref} (${r.kind}):")
            if (r.elements.size <= maxLines) r.elements.forEach { appendLine(line(it, policy)) }
            else {
                val sub = regionsUnder(r.node, r.ref)
                if (sub.isEmpty()) r.elements.forEach { appendLine(line(it, policy)) }
                else sub.forEach { s -> if (s.elements.size <= 4) s.elements.forEach { appendLine(line(it, policy)) } else appendLine(summary(s)) }
            }
        }
        return ScreenData.wrap(pkg, header(card) + cap(body, maxChars, "scroll or drill deeper"))
    }

    fun full(card: String? = null, maxChars: Int = 6000, policy: ScreenSafetyPolicy? = null): String =
        ScreenData.wrap(pkg, header(card) + cap(elements.joinToString("\n") { line(it, policy) }, maxChars, "use skeleton mode / root=@rN"))

    private fun cap(s: String, max: Int, hint: String): String = if (s.length <= max) s.trimEnd() else s.take(max).substringBeforeLast('\n') + "\n…(more; $hint)"

    // ------------------------------------------------------------------ text checks
    fun contains(text: String): Boolean {
        val t = norm(text)
        if (t.isBlank()) return false
        return elements.any { e -> norm(e.node.label).contains(t) || (e.node.editable && !e.node.password && norm(e.node.text).contains(t)) }
    }

    companion object {
        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

        fun signatureOf(frame: ScreenFrame): String {
            val s = frame.snapshot
            val sb = StringBuilder(s.packageName).append('|').append(s.activity)
            for (e in s.elements) {
                val n = e.node
                sb.append('|').append(n.shortClass).append(':').append(n.role()).append(':').append(n.viewId).append(':').append(n.path.joinToString("."))
                if (!n.editable) sb.append(':').append(n.label)
            }
            val d = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray())
            return d.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- diff

/** What an action changed: shown INSTEAD of a whole new tree (token-cheap). */
data class ScreenDiff(
    val appChanged: String?,
    val appeared: List<String>,
    val disappeared: List<String>,
    val changed: List<String>,
) {
    val isEmpty get() = appChanged == null && appeared.isEmpty() && disappeared.isEmpty() && changed.isEmpty()
    fun render(max: Int = 12): String = buildString {
        if (appChanged != null) append("App changed to ").append(appChanged).append('\n')
        if (appeared.isNotEmpty()) append("Appeared:\n").append(appeared.take(max).joinToString("\n") { "  + $it" }).append(if (appeared.size > max) "\n  …+${appeared.size - max} more\n" else "\n")
        if (disappeared.isNotEmpty()) append("Gone:\n").append(disappeared.take(max).joinToString("\n") { "  - $it" }).append(if (disappeared.size > max) "\n  …-${disappeared.size - max} more\n" else "\n")
        if (changed.isNotEmpty()) append("Changed:\n").append(changed.take(max).joinToString("\n") { "  ~ $it" }).append('\n')
        if (isEmpty) append("No visible change.")
    }.trimEnd()

    companion object {
        private fun key(n: ScreenNode) = n.role() + "|" + n.viewId + "|" + (if (n.editable) n.hint else n.label)

        fun between(old: ScreenView, new: ScreenView, policy: ScreenSafetyPolicy? = null): ScreenDiff {
            val appChanged = if (old.pkg != new.pkg) new.pkg else null
            val oldKeys = old.elements.groupingBy { key(it.node) }.eachCount().toMutableMap()
            val appeared = mutableListOf<String>()
            for (e in new.elements) {
                val k = key(e.node)
                val c = oldKeys[k] ?: 0
                if (c > 0) oldKeys[k] = c - 1 else appeared += new.line(e, policy)
            }
            val newKeys = new.elements.groupingBy { key(it.node) }.eachCount().toMutableMap()
            val disappeared = mutableListOf<String>()
            for (e in old.elements) {
                val k = key(e.node)
                val c = newKeys[k] ?: 0
                if (c > 0) newKeys[k] = c - 1 else disappeared += e.node.role() + " \"" + ScreenData.label(e.node.label.ifBlank { e.node.hint }, 50) + "\""
            }
            // Same element (same key + path), different value / checked state.
            val oldByPath = old.elements.associateBy { key(it.node) + "@" + it.node.path }
            val changed = new.elements.mapNotNull { e ->
                val o = oldByPath[key(e.node) + "@" + e.node.path] ?: return@mapNotNull null
                when {
                    e.node.editable && o.node.text != e.node.text -> new.line(e, policy)
                    e.node.checkable && o.node.checked != e.node.checked -> new.line(e, policy)
                    e.node.focused != o.node.focused && e.node.focused -> null
                    else -> null
                }
            }
            return ScreenDiff(appChanged, appeared, disappeared, changed)
        }
    }
}

// ---------------------------------------------------------------------------------------------- grounding

/** Resolves a target given by text (label/hint/id) to exactly one element — grounding by text. */
object Grounding {
    data class Match(val element: ScreenElement, val exact: Boolean)

    private fun names(e: ScreenElement): List<String> = listOf(e.node.text.takeIf { !e.node.editable }.orEmpty(), e.node.contentDescription, e.node.hint,
        e.nearbyLabel, e.node.viewId.substringAfterLast('/').replace('_', ' ')).filter { it.isNotBlank() }.map { ScreenView.norm(it) }

    /** Matches by [text] (and optional [role]); nested duplicates (a button and its own text child) count once. */
    fun find(view: ScreenView, text: String, role: String? = null): List<Match> {
        val t = ScreenView.norm(text.trim().trim('"', '\'', '“', '”'))
        if (t.isBlank()) return emptyList()
        val roleOk: (ScreenElement) -> Boolean = { e -> role.isNullOrBlank() || e.node.role() == role || (role == "button" && e.node.clickable) }
        val exact = view.elements.filter { roleOk(it) && names(it).any { n -> n == t } }
        val list = if (exact.isNotEmpty()) exact.map { Match(it, true) }
            else view.elements.filter { roleOk(it) && names(it).any { n -> n.contains(t) } }.map { Match(it, false) }
        return dedupNested(list)
    }

    private fun dedupNested(ms: List<Match>): List<Match> = ms.filter { m ->
        ms.none { o -> o !== m && o.element.node.path.size < m.element.node.path.size &&
            m.element.node.path.subList(0, o.element.node.path.size) == o.element.node.path }
    }

    /** Exactly one element, preferring exact matches; null when 0 or >1 (caller reports NOT_FOUND / AMBIGUOUS). */
    fun unique(view: ScreenView, text: String, role: String? = null): ScreenElement? = find(view, text, role).singleOrNull()?.element
}
