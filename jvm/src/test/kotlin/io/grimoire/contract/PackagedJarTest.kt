package io.grimoire.contract

import io.grimoire.api.source.Source
import io.grimoire.api.source.SourceInfo
import io.grimoire.api.source.http.HttpSource
import io.grimoire.api.network.defaultOkHttpClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.security.MessageDigest
import kotlin.test.*

class PackagedJarTest {
    @Test fun `Royal Road packaged JAR passes its source contract`() = runBlocking {
        contract("en-royalroad", RoyalRoadContract::run)
    }

    @Test fun `Galaxy packaged JAR passes its browser source contract`() = runBlocking {
        contract("ar-galaxynovels", GalaxyNovelsContract::run)
    }

    private suspend fun contract(module: String, runContract: suspend (Source) -> Map<String, String>) {
        val file = File(System.getProperty("extension.$module.jar"))
        val metadata = JSONObject(File(System.getProperty("extension.$module.identity")).readText())
        for (field in listOf("package", "sourceClass", "versionCode", "versionName", "sourceVersionCode")) {
            val expected = requireNotNull(System.getProperty("extension.$module.expected.$field"))
            assertEquals(expected, metadata.getString(field), "Current $module declarations: $field")
        }
        val attributes = JarFile(file).use { jar ->
            val classes = jar.entries().toList().filter { it.name.endsWith(".class") }
            assertTrue(classes.isNotEmpty())
            classes.forEach { entry ->
                assertTrue(entry.name.startsWith(metadata.getString("package").replace('.', '/') + "/"), entry.name)
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                assertEquals(55, ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255))
                assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("android/"), entry.name)
            }
            jar.manifest.mainAttributes
        }
        assertEquals("1", attributes.getValue("Grimoire-Extension-Format"))
        assertEquals("1.5", attributes.getValue("Grimoire-Core-Api"))
        listOf("package" to "Grimoire-Source-Package", "sourceClass" to "Grimoire-Source-Class",
            "versionCode" to "Grimoire-Version-Code", "versionName" to "Grimoire-Version-Name",
            "sourceVersionCode" to "Grimoire-Source-Version-Code").forEach { (key, header) ->
            assertEquals(metadata.getString(key), attributes.getValue(header))
        }
        // Parser tests compile these sources too. Hide their copies from the child loader
        // so this test can only instantiate code inside the artifact being shipped.
        val parent = object : ClassLoader(Source::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("io.grimoire.extension.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        URLClassLoader(arrayOf(file.toURI().toURL()), parent).use { loader ->
            val type = loader.loadClass(attributes.getValue("Grimoire-Source-Class"))
            assertSame(loader, type.classLoader)
            val source = type.getDeclaredConstructor().newInstance() as Source
            assertSame(defaultOkHttpClient().cookieJar, (source as HttpSource).client.cookieJar)
            assertEquals(metadata.getString("sourceVersionCode").toInt(), type.getAnnotation(SourceInfo::class.java).versionCode)
            val result = runContract(source)
            val report = JSONObject().put("contract", JSONObject(result))
                .put("jarSha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            File(System.getProperty("extension.$module.result")).writeText(report.toString(2))
        }
    }
}
