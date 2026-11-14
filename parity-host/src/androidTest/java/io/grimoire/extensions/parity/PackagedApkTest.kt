package io.grimoire.extensions.parity

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import io.grimoire.api.network.SourceNetwork
import io.grimoire.api.source.Source
import io.grimoire.contract.RoyalRoadContract
import io.grimoire.contract.RoyalRoadContractPlatform
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Loads only the installed APK's parser; the host compiles the shared contract, not source implementations. */
@RunWith(AndroidJUnit4::class)
class PackagedApkTest {
    @Suppress("DEPRECATION")
    @Test
    fun installedApkRunsTheSharedParserContract() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = context.packageManager.getPackageInfo("io.grimoire.extension.en.royalroad", PackageManager.GET_META_DATA)
        val app = requireNotNull(pkg.applicationInfo)
        val entry = requireNotNull(app.metaData.getString("grimoire.extension.class"))
        val minimumApi = app.metaData.getString("grimoire.extension.minApi")
        assertEquals(BuildConfig.HOST_CORE_API, minimumApi)
        SourceNetwork.install(RoyalRoadContractPlatform())
        val loader = DexClassLoader(app.sourceDir, context.codeCacheDir.absolutePath, null, Source::class.java.classLoader)
        val type = loader.loadClass(entry)
        assertSame(loader, type.classLoader)
        val source = type.getDeclaredConstructor().newInstance() as Source
        val contract = RoyalRoadContract.run(source)
        val hash = MessageDigest.getInstance("SHA-256")
        File(app.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        val report = JSONObject()
            .put("contract", JSONObject(contract))
            .put("apkSha256", hash.digest().joinToString("") { "%02x".format(it) })
            .put("apkPackage", pkg.packageName)
            .put("androidApiLevel", Build.VERSION.SDK_INT)
            .put("apkCoreApi", minimumApi)
            .put("apkVersionCode", if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong())
            .put("apkVersionName", pkg.versionName)
        File(context.filesDir, "contract-android.json").writeText(report.toString(2))
    }
}
