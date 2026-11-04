package com.offlineagent.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {
    private val pk = FakePackages()
    private val cat = AppCatalogue(pk, testPolicy())

    @Test fun buildsCapabilitiesAndKinds() {
        val wa = cat.entry("com.whatsapp")!!
        assertTrue(wa.can(Capability.LAUNCH) && wa.can(Capability.SEND_TEXT) && wa.can(Capability.SEND_IMAGE))
        assertFalse(wa.can(Capability.VIEW_WEB))
        assertEquals(AppKind.PEOPLE, wa.kind)
        assertEquals(AppKind.PEOPLE, cat.entry("com.google.android.gm")!!.kind)
        assertEquals(AppKind.BROWSER, cat.entry("com.android.chrome")!!.kind)
        assertEquals(AppKind.MAPS, cat.entry("com.google.android.apps.maps")!!.kind)
        assertEquals(AppKind.MEDIA, cat.entry("com.spotify.music")!!.kind)
        assertEquals(AppKind.STORE, cat.entry("com.android.vending")!!.kind)
    }

    @Test fun blockedAppsAreMarkedAndHaveNoCapabilities() {
        listOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "com.x8bit.bitwarden", "com.google.android.apps.authenticator2",
            "com.android.settings", "com.offlineagent.app", "com.example.bank.mobile").forEach { p ->
            val e = cat.entry(p)!!
            assertNotNull(p, e.blocked); assertTrue(p, e.capabilities.isEmpty())
        }
        assertNull(cat.entry("com.whatsapp")!!.blocked)
    }

    @Test fun fuzzyFindAndResolve() {
        assertEquals("com.whatsapp", cat.find("whatsapp").first().entry.pkg)
        assertEquals("com.whatsapp", cat.find("whats app").first().entry.pkg)
        assertEquals("com.whatsapp", cat.find("watsapp").first().entry.pkg)   // typo
        assertEquals("com.google.android.youtube", cat.find("yt").first().entry.pkg) // alias
        assertEquals("com.google.android.youtube", cat.find("you tube").first().entry.pkg)
        assertEquals("com.android.vending", cat.find("play store").first().entry.pkg)
        assertTrue(cat.find("zzzqqq").isEmpty())
        assertEquals("com.google.android.gm", (cat.resolve("com.google.android.gm") as AppCatalogue.Resolution.Found).entry.pkg)
        assertEquals("com.spotify.music", (cat.resolve("Spotify") as AppCatalogue.Resolution.Found).entry.pkg)
        assertTrue(cat.resolve("nothing like it") is AppCatalogue.Resolution.NotFound)
    }

    @Test fun refreshPicksUpInstallsAndRemovals() {
        val v0 = cat.entries.size
        val ver = cat.version
        pk.apps += RawApp("org.telegram.messenger", "Telegram"); pk.caps[Capability.SEND_TEXT]!! += "org.telegram.messenger"
        cat.refresh()
        assertEquals(v0 + 1, cat.entries.size)
        assertTrue(cat.entry("org.telegram.messenger")!!.can(Capability.SEND_TEXT))
        assertEquals(AppKind.PEOPLE, cat.entry("org.telegram.messenger")!!.kind)
        pk.apps.removeAll { it.pkg == "com.spotify.music" }
        cat.refresh()
        assertNull(cat.entry("com.spotify.music"))
        assertTrue(cat.version > ver)
    }

    @Test fun catalogueIsBuiltOnceLazily() {
        cat.entries; val q = pk.queries; cat.entries; cat.find("maps")
        assertEquals(q, pk.queries)
        assertEquals(1 + Probes.ALL.size, q)
    }
}

class IntentBuilderTest {
    private val pk = FakePackages()
    private val policy = testPolicy()
    private val cat = AppCatalogue(pk, policy)
    private val b = IntentBuilder(policy)
    private fun ok(r: BuildResult) = (r as? BuildResult.Ok)?.intent ?: error("rejected: $r")
    private fun rejected(r: BuildResult) = r as? BuildResult.Rejected ?: error("expected rejection, got $r")

    @Test fun openUsesLaunchIntent() {
        val i = ok(b.open(cat.entry("com.google.android.youtube")!!))
        assertEquals("com.google.android.youtube", i.launchPackage)
        assertTrue(rejected(b.open(cat.entry("com.phonepe.app")!!)).blocked)
        assertTrue(rejected(b.open(cat.entry("com.android.settings")!!)).blocked)
        assertTrue(rejected(b.open(cat.entry("com.offlineagent.app")!!)).blocked)
    }

    @Test fun shareTextAndFiles() {
        val wa = cat.entry("com.whatsapp")!!
        val t = ok(b.share(wa, "hello", emptyList()))
        assertEquals(AppIntent.ACTION_SEND, t.action); assertEquals("text/plain", t.type); assertEquals("com.whatsapp", t.pkg); assertEquals("hello", t.extras[AppIntent.EXTRA_TEXT])
        val img = SharedFile("exports/a.png", "content://x/a.png", "image/png", "a.png")
        assertEquals("image/png", ok(b.share(wa, "", listOf(img))).type)
        val pdf = SharedFile("exports/r.pdf", "content://x/r.pdf", "application/pdf", "r.pdf")
        rejected(b.share(wa, "", listOf(pdf)))                                    // WhatsApp (fake) has no SEND */*
        val gm = cat.entry("com.google.android.gm")!!
        val multi = ok(b.share(gm, "see attached", listOf(img, pdf)))
        assertEquals(AppIntent.ACTION_SEND_MULTIPLE, multi.action); assertEquals("*/*", multi.type); assertEquals(2, multi.streams.size)
        val chooser = ok(b.share(null, "pick an app", emptyList()))
        assertNull(chooser.pkg); assertNotNull(chooser.chooserTitle)
        rejected(b.share(cat.entry("com.google.android.apps.maps")!!, "x", emptyList()))  // maps can't receive text
        rejected(b.share(wa, "", emptyList()))
        assertTrue(rejected(b.share(wa, "pay here upi://pay?pa=a@ybl&am=1", emptyList())).blocked)
        assertTrue(rejected(b.share(cat.entry("com.phonepe.app")!!, "x", emptyList())).blocked)
    }

    @Test fun viewLinks() {
        val v = ok(b.view("https://www.youtube.com/watch?v=1", cat.entry("com.google.android.youtube")))
        assertEquals(AppIntent.ACTION_VIEW, v.action); assertTrue(AppIntent.CATEGORY_BROWSABLE in v.categories)
        assertEquals(AppIntent.ACTION_SENDTO, ok(b.view("mailto:a@b.com", null)).action)
        assertEquals(AppIntent.ACTION_DIAL, ok(b.view("tel:+911234567890", null)).action)
        assertEquals("geo:0,0?q=charminar", ok(b.view("geo:0,0?q=charminar", null)).data)
        assertTrue(rejected(b.view("upi://pay?pa=x@ybl", null)).blocked)
        assertTrue(rejected(b.view("tel:*99#", null)).blocked)
        assertTrue(rejected(b.view("intent://x#Intent;scheme=upi;end", null)).blocked)
        assertFalse(rejected(b.view("www.example.com", null)).blocked) // no scheme: not a link, not "blocked"
    }

    @Test fun searchUsesDeepLinksThenIntents() {
        val yt = ok(b.search("lofi beats", cat.entry("com.google.android.youtube")))
        assertEquals("https://www.youtube.com/results?search_query=lofi%20beats", yt.data); assertEquals("com.google.android.youtube", yt.pkg)
        assertEquals("geo:0,0?q=biryani%20near%20me", ok(b.search("biryani near me", cat.entry("com.google.android.apps.maps"))).data)
        val chrome = ok(b.search("weather", cat.entry("com.android.chrome")))
        assertEquals(AppIntent.ACTION_WEB_SEARCH, chrome.action); assertEquals("weather", chrome.extras[AppIntent.EXTRA_QUERY])
        val web = ok(b.search("kotlin coroutines", null)); assertEquals(AppIntent.ACTION_WEB_SEARCH, web.action); assertNull(web.pkg)
        assertEquals("spotify:search:lofi", ok(b.search("lofi", cat.entry("com.spotify.music"))).data)
        rejected(b.search("x", cat.entry("com.example.notes")))   // no known search entry
        rejected(b.search("  ", null))
    }
}
