package id.churn

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkSha256Test {

    @get:Rule
    val folder = TemporaryFolder()

    private val files = mapOf(
        "classes10.dex" to "dex10",
        "res/raw/a b.txt" to "x",
        "classes.dex" to "dex1",
        "assets/empty" to "",
        "classes2.dex" to "dex2",
    )

    /** What the Coverage build job's shell commands (build.yml) give for [files]. */
    @Test
    fun hashesTheFilesAsTheShellDoes() {
        val apk = apk(files + ("META-INF/CERT.SF" to "signature"), directories = listOf("res/", "res/raw/"))

        assertEquals("6ff14120 1fb6e21a 3e12eee1 d890d750 55129c1b 7b141e6f 3aac1b3e b261f0ab", apkSha256(apk))
    }

    @Test
    fun leavesOutMetaInfAndTheOrderOfTheFiles() {
        val one = apk(files + ("META-INF/CERT.SF" to "one key"))
        val other = apk(files.entries.reversed().associate { it.toPair() } + ("META-INF/CERT.SF" to "another key"))

        assertEquals(apkSha256(one), apkSha256(other))
    }

    @Test
    fun changesWithAFile() {
        assertNotEquals(apkSha256(apk(files)), apkSha256(apk(files + ("classes2.dex" to "dex2, changed"))))
    }

    private fun apk(files: Map<String, String>, directories: List<String> = emptyList()): File =
        folder.newFile().also { apk ->
            ZipOutputStream(apk.outputStream()).use { zip ->
                for (directory in directories) zip.putNextEntry(ZipEntry(directory))
                for ((name, text) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                }
            }
        }
}
