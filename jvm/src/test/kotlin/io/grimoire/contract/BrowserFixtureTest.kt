package io.grimoire.contract

import io.grimoire.api.network.SourceDocumentDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowserFixtureTest {
    @Test fun `tokens without the extraction protocol cannot produce synthetic content`() = runBlocking {
        GalaxyNovelsFixtures.reset()
        GalaxyNovelsFixtures.ready = true
        try {
            assertFailsWith<IllegalStateException> {
                GalaxyNovelsFixtures.read(
                    "https://galaxynovels.com/novels/?sort=popular&period=month",
                    "location.href; document.documentElement.outerHTML",
                    1_000,
                ) { if (it.status == 403) SourceDocumentDecision.Wait else SourceDocumentDecision.Accept }
            }
            assertTrue(GalaxyNovelsFixtures.browserRequests.isEmpty())
        } finally {
            GalaxyNovelsFixtures.reset()
        }
    }
}
