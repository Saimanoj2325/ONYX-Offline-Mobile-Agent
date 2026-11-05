package com.offlineagent.core.runtime.phone

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 6.2: what a screen "looks like" without keeping its words. View ids are kept as short names (they are
 * layout names, not content: "entry", "send", "conversations_row_contact_name"); visible texts are kept only as
 * 32-bit hashes, so a saved macro never stores message previews and a debug report can compare screens without
 * revealing them. Similarity is a weighted Jaccard over ids and text hashes (idea adapted from Mika's elementsHash /
 * OperationGuards screen fingerprints, Apache-2.0 — see NOTICE).
 */
@Serializable
data class ScreenFingerprint(
    val packageName: String = "",
    val activity: String = "",
    val ids: List<String> = emptyList(),
    val texts: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = ids.isEmpty() && texts.isEmpty()

    fun similarity(o: ScreenFingerprint?): Double {
        if (o == null) return 0.0
        if (packageName.isNotBlank() && o.packageName.isNotBlank() && packageName != o.packageName) return 0.0
        val idS = jaccard(ids, o.ids)
        val txS = jaccard(texts, o.texts)
        val haveIds = ids.isNotEmpty() || o.ids.isNotEmpty()
        val haveTx = texts.isNotEmpty() || o.texts.isNotEmpty()
        var s = when {
            haveIds && haveTx -> 0.65 * idS + 0.35 * txS
            haveIds -> idS
            haveTx -> txS
            else -> 1.0
        }
        if (activity.isNotBlank() && o.activity.isNotBlank() && activity != o.activity) s *= 0.8
        return s
    }

    /** Short words for reports: package / activity and a few view ids (never visible text). */
    fun describe(maxIds: Int = 6): String = buildString {
        append(packageName.ifBlank { "?" })
        if (activity.isNotBlank()) append(" / ").append(activity.substringAfterLast('.'))
        if (ids.isNotEmpty()) append(" · ids like ").append(ids.take(maxIds).joinToString(", "))
        append(" · ").append(texts.size).append(" text hashes")
    }

    companion object {
        private fun jaccard(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val sa = a.toSet(); val sb = b.toSet()
            val inter = sa.count { it in sb }
            val union = sa.size + sb.size - inter
            return if (union == 0) 1.0 else inter.toDouble() / union
        }

        fun hash(s: String): String {
            var h = 0x811c9dc5.toInt()
            for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toInt() and 0xff); h *= 0x01000193 }
            return "%08x".format(h)
        }

        fun of(s: ScreenSnapshot?, maxIds: Int = 80, maxTexts: Int = 80): ScreenFingerprint? {
            s ?: return null
            val ids = LinkedHashSet<String>()
            val texts = LinkedHashSet<String>()
            fun walk(n: ScreenNode) {
                val id = n.viewId.substringAfterLast('/')
                if (id.isNotBlank() && ids.size < maxIds) ids += id
                if (!n.editable && !n.password) {
                    val t = MacroText.norm(n.text.ifBlank { n.contentDescription })
                    if (t.length in 1..40 && texts.size < maxTexts && !MacroText.volatile(t)) texts += hash(t)
                }
                n.children.forEach(::walk)
            }
            walk(s.root)
            return ScreenFingerprint(s.packageName, s.activity, ids.toList(), texts.toList())
        }
    }
}

/** Text helpers shared by recording, matching and the screen agent. */
object MacroText {
    fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim().trim('"', '\'', '“', '”')

    private val TIME = Regex("^(\\d{1,2}[:.]\\d{2}( ?[ap]\\.?m\\.?)?|yesterday|today|now|\\d+ ?(min|mins|h|hr|d|w)( ago)?|\\d{1,2}/\\d{1,2}/\\d{2,4})$")
    /** Timestamps, unread counters and similar text that changes between runs (never used as an identity). */
    fun volatile(t: String): Boolean = TIME.matches(t) || t.all { it.isDigit() || it == ' ' || it == '+' }

    /** 0..1 similarity: token Jaccard blended with a normalised edit distance (for small spelling / emoji drift). */
    fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val ta = x.split(' ').toSet(); val tb = y.split(' ').toSet()
        val tok = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        val lev = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
        val contains = if (x.contains(y) || y.contains(x)) min(x.length, y.length).toDouble() / max(x.length, y.length) else 0.0
        return maxOf(tok, lev, contains)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.length > 64 || b.length > 64) return max(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** "Mom" → "M**" for debug reports (contact / chat names are private even if they are not message contents). */
    fun mask(s: String): String {
        val t = s.trim()
        if (t.length <= 2) return "*".repeat(t.length)
        return t.take(2) + "*".repeat(min(6, t.length - 2))
    }
}

/** Path → node lookups and ancestor chains over one snapshot (paths are the child-index paths the service assigns). */
class TreeIndex(val snapshot: ScreenSnapshot) {
    private val byPath = HashMap<List<Int>, ScreenNode>()
    /** False when the tree has no usable paths (hand-made test snapshots): ancestor features are then skipped. */
    val hasPaths: Boolean

    init {
        var dup = false
        fun walk(n: ScreenNode) { if (byPath.put(n.path, n) != null) dup = true; n.children.forEach(::walk) }
        walk(snapshot.root)
        hasPaths = !dup
    }

    fun node(path: List<Int>): ScreenNode? = if (hasPaths) byPath[path] else null

    /** Root first, parent last. */
    fun ancestors(path: List<Int>): List<ScreenNode> = if (!hasPaths) emptyList() else (0 until path.size).mapNotNull { byPath[path.subList(0, it)] }

    fun isListContainer(n: ScreenNode): Boolean = n.scrollable || LIST_CLASS.containsMatchIn(n.className)

    /** Nearest scrollable / list ancestor of [path], or null. */
    fun listAncestor(path: List<Int>): ScreenNode? = ancestors(path).lastOrNull { isListContainer(it) }

    fun under(n: ScreenNode, path: List<Int>) = path.size > n.path.size && path.subList(0, n.path.size) == n.path

    /** Row = the list's direct child on the way to [path]. */
    fun rowOf(path: List<Int>): ScreenNode? {
        val list = listAncestor(path) ?: return null
        return node(path.subList(0, list.path.size + 1))
    }

    companion object {
        val LIST_CLASS = Regex("RecyclerView|ListView|GridView|ScrollView|LazyColumn|LazyList", RegexOption.IGNORE_CASE)
    }
}

/** Builds rich [NodeSelector]s from a live tree (used while recording). */
object TargetCapture {
    private val NAME_ID = Regex("(name|title|contact|label|subject|header|display)", RegexOption.IGNORE_CASE)

    /** The label a list row is known by ("Mom" for a WhatsApp chat row): a name/title view first, else the first stable text. */
    fun rowLabel(row: ScreenNode): String {
        val texts = ArrayList<ScreenNode>()
        fun walk(n: ScreenNode) {
            if (!n.editable && !n.password && n.text.isNotBlank()) texts += n
            n.children.forEach(::walk)
        }
        walk(row)
        val stable = texts.filter { !MacroText.volatile(MacroText.norm(it.text)) && it.text.length <= 60 }
        val named = stable.firstOrNull { NAME_ID.containsMatchIn(it.viewId.substringAfterLast('/')) }
        val pick = named ?: stable.minWithOrNull(compareBy<ScreenNode>({ it.bounds.top }, { it.bounds.left }))
        return pick?.text?.trim().orEmpty().ifBlank { row.contentDescription.trim().take(60) }
    }

    fun selector(n: ScreenNode, pkg: String, screen: ScreenSnapshot?, path: List<Int>?): NodeSelector {
        val base = NodeSelector(pkg, n.viewId, if (n.editable) "" else n.text.take(80), n.contentDescription.take(80), n.className, n.hint.take(80), editable = n.editable)
        if (screen == null) return base
        val idx = TreeIndex(screen)
        val p = path ?: return base.copy(activity = screen.activity, windowTitle = screen.windowTitle.take(60))
        val live = idx.node(p)?.takeIf { it.className == n.className || n.className.isBlank() } ?: return base.copy(activity = screen.activity, windowTitle = screen.windowTitle.take(60))
        val anc = idx.ancestors(p).takeLast(8).map { AncestorRef(it.viewId, it.className, it.path.lastOrNull() ?: -1) }
        val list = idx.listAncestor(p)
        val row = idx.rowOf(p)
        val rowLabel = row?.let { rowLabel(it) }.orEmpty()
        val rootB = screen.root.bounds
        val w = rootB.width.takeIf { it > 0 } ?: 1
        val h = rootB.height.takeIf { it > 0 } ?: 1
        val b = live.bounds
        // Inside a list with a row label, the clicked node's own text may be a message preview: never depend on it.
        val ownText = if (list != null && rowLabel.isNotBlank() && !MacroText.norm(base.text).equals(MacroText.norm(rowLabel))) "" else base.text
        return base.copy(
            text = ownText,
            activity = screen.activity, windowTitle = screen.windowTitle.take(60), ancestors = anc,
            listItemLabel = if (list != null) rowLabel.take(80) else "", listId = list?.viewId.orEmpty(), inList = list != null,
            relX = (b.centerX - rootB.left).toFloat() / w, relY = (b.centerY - rootB.top).toFloat() / h,
            relW = b.width.toFloat() / w, relH = b.height.toFloat() / h,
        )
    }
}

enum class MatchStrategy(val label: String) {
    ID_TEXT("resource id + text"),
    LIST_ITEM("list item label"),
    TEXT_EXACT("exact text/description"),
    FUZZY_TEXT("similar text"),
    ANCESTOR_PATH("ancestor path"),
    REL_BOUNDS("screen position"),
}

data class MatchAttempt(val strategy: MatchStrategy, val candidates: Int, val note: String = "", val nearMisses: List<Pair<String, Double>> = emptyList())
data class MatchResult(val element: ScreenElement?, val strategy: MatchStrategy?, val attempts: List<MatchAttempt>)

/**
 * Replay by matching, not coordinates. Strategies in order: resource id + text → list item label → exact text /
 * description → fuzzy text → ancestor path → relative bounds (last resort; never for list rows or irreversible steps,
 * where a wrong guess would tap the wrong chat or press the wrong button). Each strategy must find exactly ONE element
 * (after tie-breaks), otherwise the next one is tried.
 */
object TargetMatcher {
    val ORDER = MatchStrategy.entries.toList()

    fun fill(s: String, params: Map<String, String>) = MacroMatcher.fill(s, params)

    fun match(
        sel0: NodeSelector, s: ScreenSnapshot, params: Map<String, String> = emptyMap(),
        exclude: Set<List<Int>> = emptySet(), from: MatchStrategy = MatchStrategy.ID_TEXT, allowBounds: Boolean = true,
    ): MatchResult {
        val sel = sel0.copy(text = fill(sel0.text, params), contentDescription = fill(sel0.contentDescription, params), listItemLabel = fill(sel0.listItemLabel, params))
        val idx = TreeIndex(s)
        val els = s.elements.filter { it.node.path !in exclude || !idx.hasPaths }
        val attempts = ArrayList<MatchAttempt>()
        for (st in ORDER.dropWhile { it != from }) {
            if (st == MatchStrategy.REL_BOUNDS && (!allowBounds || sel.inList)) { attempts += MatchAttempt(st, 0, if (sel.inList) "skipped (list row: position is not reliable)" else "skipped (step needs an exact match)"); continue }
            val (found, att) = run(st, sel, s, idx, els)
            attempts += att
            if (found != null && (found.node.path !in exclude || !idx.hasPaths)) return MatchResult(found, st, attempts)
        }
        return MatchResult(null, null, attempts)
    }

    private fun kindOk(sel: NodeSelector, n: ScreenNode) = !sel.editable || n.editable

    private fun eqLabel(n: ScreenNode, t: String): Boolean {
        val x = MacroText.norm(t)
        return x.isNotEmpty() && ((!n.editable && MacroText.norm(n.text) == x) || MacroText.norm(n.contentDescription) == x || (n.editable && MacroText.norm(n.hint) == x))
    }

    /** Prefer the clickable ancestor among elements (the row, not its title text). */
    private fun promote(e: ScreenElement, s: ScreenSnapshot, idx: TreeIndex, stopAt: ScreenNode? = null): ScreenElement {
        if (e.node.clickable || e.node.editable || !idx.hasPaths) return e
        val anc = idx.ancestors(e.node.path).reversed()
        for (a in anc) {
            if (stopAt != null && a.path.size <= stopAt.path.size) break
            if (a.clickable || a.longClickable) return s.elements.firstOrNull { it.node.path == a.path } ?: e
        }
        return e
    }

    private fun dedup(c: List<ScreenElement>): List<ScreenElement> = c.distinctBy { it.node.path.takeIf { p -> p.isNotEmpty() } ?: it.id }

    private fun ancestorScore(sel: NodeSelector, idx: TreeIndex, n: ScreenNode): Double {
        if (sel.ancestors.isEmpty() || !idx.hasPaths) return 0.0
        val mine = idx.ancestors(n.path).takeLast(sel.ancestors.size)
        if (mine.isEmpty()) return 0.0
        var hit = 0.0
        val pairs = sel.ancestors.reversed().zip(mine.reversed())
        for ((want, have) in pairs) {
            val idOk = want.viewId.isNotBlank() && want.viewId == have.viewId
            val clsOk = want.className == have.className
            hit += when { idOk -> 1.0; want.viewId.isBlank() && clsOk -> 0.8; clsOk -> 0.4; else -> 0.0 }
        }
        return hit / sel.ancestors.size
    }

    private fun dist(sel: NodeSelector, s: ScreenSnapshot, n: ScreenNode): Double {
        if (sel.relX < 0) return Double.MAX_VALUE
        val r = s.root.bounds
        val w = r.width.takeIf { it > 0 } ?: return Double.MAX_VALUE
        val h = r.height.takeIf { it > 0 } ?: return Double.MAX_VALUE
        return hypot((n.bounds.centerX - r.left).toDouble() / w - sel.relX, (n.bounds.centerY - r.top).toDouble() / h - sel.relY)
    }

    /** Several candidates: class, ancestor path, then position decide; null when they cannot. */
    private fun tieBreak(sel: NodeSelector, s: ScreenSnapshot, idx: TreeIndex, c: List<ScreenElement>): ScreenElement? {
        if (c.size <= 1) return c.singleOrNull()
        val sameClass = c.filter { it.node.className == sel.className }.ifEmpty { c }
        if (sameClass.size == 1) return sameClass.single()
        val scored = sameClass.map { it to ancestorScore(sel, idx, it.node) }.sortedByDescending { it.second }
        if (scored[0].second > 0 && scored[0].second - scored[1].second >= 0.2) return scored[0].first
        if (!sel.inList) {
            val byDist = sameClass.map { it to dist(sel, s, it.node) }.sortedBy { it.second }
            if (byDist[0].second < 0.08 && byDist[1].second > byDist[0].second * 2) return byDist[0].first
        }
        return null
    }

    private fun inList(sel: NodeSelector, idx: TreeIndex, n: ScreenNode): Boolean {
        if (!idx.hasPaths) return true
        val list = idx.listAncestor(n.path) ?: return false
        return sel.listId.isBlank() || list.viewId == sel.listId || list.viewId.isBlank()
    }

    private fun run(st: MatchStrategy, sel: NodeSelector, s: ScreenSnapshot, idx: TreeIndex, els: List<ScreenElement>): Pair<ScreenElement?, MatchAttempt> {
        fun pick(c: List<ScreenElement>, note: String = ""): Pair<ScreenElement?, MatchAttempt> {
            val d = dedup(c)
            val one = tieBreak(sel, s, idx, d)
            return one to MatchAttempt(st, d.size, if (one == null && d.size > 1) "ambiguous ($note${d.size} matches)" else note.trimEnd(' ', ','))
        }
        return when (st) {
            MatchStrategy.ID_TEXT -> {
                if (sel.viewId.isBlank()) return null to MatchAttempt(st, 0, "no id recorded")
                val label = sel.text.ifBlank { sel.contentDescription }
                var c = els.filter { it.node.viewId == sel.viewId && kindOk(sel, it.node) && (label.isBlank() || eqLabel(it.node, label)) }
                if (c.size > 1 && sel.listItemLabel.isNotBlank() && idx.hasPaths)
                    c = c.filter { e -> idx.rowOf(e.node.path)?.let { MacroText.norm(TargetCapture.rowLabel(it)) == MacroText.norm(sel.listItemLabel) } ?: false }
                if (c.size > 1 && sel.inList && label.isBlank()) return null to MatchAttempt(st, c.size, "ambiguous (${c.size} list rows share the id)")
                pick(c)
            }
            MatchStrategy.LIST_ITEM -> {
                if (sel.listItemLabel.isBlank()) return null to MatchAttempt(st, 0, "not a list item")
                val want = MacroText.norm(sel.listItemLabel)
                val hits = els.filter { !it.node.editable && MacroText.norm(it.node.text.ifBlank { it.node.contentDescription }) == want && inList(sel, idx, it.node) }
                val rows = hits.map { e -> promote(e, s, idx, idx.listAncestor(e.node.path)) }
                pick(rows)
            }
            MatchStrategy.TEXT_EXACT -> {
                val label = sel.text.ifBlank { sel.contentDescription.ifBlank { if (sel.editable) sel.hint else "" } }
                if (label.isBlank()) return null to MatchAttempt(st, 0, "no text recorded")
                val c = els.filter { kindOk(sel, it.node) && (eqLabel(it.node, label) || (sel.contentDescription.isNotBlank() && eqLabel(it.node, sel.contentDescription))) }
                    .map { promote(it, s, idx) }
                pick(c)
            }
            MatchStrategy.FUZZY_TEXT -> {
                val label = sel.listItemLabel.ifBlank { sel.text.ifBlank { sel.contentDescription.ifBlank { if (sel.editable) sel.hint else "" } } }
                if (label.isBlank()) return null to MatchAttempt(st, 0, "no text recorded")
                val scored = els.filter { kindOk(sel, it.node) }.mapNotNull { e ->
                    val n = e.node
                    val name = if (n.editable) n.hint else n.text.ifBlank { n.contentDescription }
                    if (name.isBlank() || name.length > 80) null else e to MacroText.similarity(label, name)
                }.sortedByDescending { it.second }
                val near = scored.take(3).filter { it.second >= 0.4 }.map { MacroText.mask(it.first.node.label) to Math.round(it.second * 100) / 100.0 }
                val best = scored.firstOrNull()
                if (best == null || best.second < 0.75) return null to MatchAttempt(st, 0, "nothing close enough", near)
                val second = scored.getOrNull(1)?.second ?: 0.0
                if (best.second - second < 0.1 && best.second < 1.0) return null to MatchAttempt(st, scored.count { it.second >= 0.75 }, "ambiguous", near)
                val el = if (sel.inList) promote(best.first, s, idx, idx.listAncestor(best.first.node.path)) else promote(best.first, s, idx)
                el to MatchAttempt(st, 1, "", near)
            }
            MatchStrategy.ANCESTOR_PATH -> {
                if (sel.ancestors.isEmpty() || !idx.hasPaths) return null to MatchAttempt(st, 0, "no ancestor path recorded")
                if (sel.inList) return null to MatchAttempt(st, 0, "skipped (list rows are reused)")
                val c = els.filter { it.node.className == sel.className && kindOk(sel, it.node) }
                    .map { it to ancestorScore(sel, idx, it.node) }.filter { it.second >= 0.8 }.sortedByDescending { it.second }
                if (c.isEmpty()) return null to MatchAttempt(st, 0, "no element with that ancestor path")
                if (c.size > 1 && abs(c[0].second - c[1].second) < 0.05) {
                    val byDist = c.map { it.first }.sortedBy { dist(sel, s, it.node) }
                    val d0 = dist(sel, s, byDist[0].node); val d1 = dist(sel, s, byDist[1].node)
                    return if (d0 < 0.1 && d1 > d0 * 2) byDist[0] to MatchAttempt(st, c.size, "closest of ${c.size}") else null to MatchAttempt(st, c.size, "ambiguous (${c.size} matches)")
                }
                c[0].first to MatchAttempt(st, c.size)
            }
            MatchStrategy.REL_BOUNDS -> {
                if (sel.relX < 0) return null to MatchAttempt(st, 0, "no position recorded")
                val c = els.filter { val n = it.node; n.interactive && kindOk(sel, n) && (n.className == sel.className || (n.clickable && !sel.editable)) }
                    .map { it to dist(sel, s, it.node) }.filter { it.second <= 0.06 }.sortedBy { it.second }
                if (c.isEmpty()) return null to MatchAttempt(st, 0, "nothing at that spot")
                if (c.size > 1 && c[1].second < c[0].second * 1.5) return null to MatchAttempt(st, c.size, "ambiguous (${c.size} near that spot)")
                c[0].first to MatchAttempt(st, c.size)
            }
        }
    }
}
