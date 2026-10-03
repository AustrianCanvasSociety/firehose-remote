package app.burro.firehoseremote.protocol

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Grep gate: the `protocol/` package must be pure Kotlin/JVM with no `android.*`
 * or `java.net.*` imports. That rule is what keeps the protocol layer testable
 * on the JVM without a device. This test encodes it so a stray import trips
 * `testDebugUnitTest`, not just a manual grep.
 */
class ProtocolPackageIsolationTest {

    @Test
    fun noAndroidOrJavaNetImportsInProtocolPackage() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java/app/burro/firehoseremote/protocol"),
            File(cwd, "app/src/main/java/app/burro/firehoseremote/protocol"),
            File(cwd.parentFile, "app/src/main/java/app/burro/firehoseremote/protocol")
        )
        val protocolDir = candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: fail(
                "protocol/ source dir not found; searched relative to cwd=${cwd.absolutePath}"
            ).let { return }

        val offenders = mutableListOf<String>()
        protocolDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("import android.") ||
                            trimmed.startsWith("import java.net.")
                        ) {
                            offenders += "${file.name}:${i + 1}: $line"
                        }
                    }
                }
            }
        assertTrue(
            "protocol/ package must have zero android.* or java.net.* imports; found: $offenders",
            offenders.isEmpty()
        )
    }
}
