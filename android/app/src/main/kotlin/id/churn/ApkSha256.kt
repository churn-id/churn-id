package id.churn

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * The SHA-256 that names a build, as CI's Coverage build job shows it for the coverage build: of [apk]'s files apart
 * from META-INF, where signing can put files that differ from computer to computer. It hashes a line for each file, in
 * the order of their names' bytes, with the file's SHA-256 and its name, as `sha256sum` writes them for the files that
 * `unzip` extracts (build.yml). It comes in groups of eight digits, which are easier to compare and which the debug log
 * keeps, as it drops longer numbers.
 */
fun apkSha256(apk: File): String = ZipFile(apk).use { zip ->
    val files = zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
    val lines = files.sortedWith(compareBy(NAME_BYTES) { it.name }).joinToString("") { file ->
        "${sha256(zip.getInputStream(file).use { it.readBytes() })}  ./${file.name}\n"
    }
    sha256(lines.toByteArray()).chunked(8).joinToString(" ")
}

/** Orders names by their UTF-8 bytes, as `LC_ALL=C sort` does. */
private val NAME_BYTES = Comparator<String> { a, b ->
    val x = a.toByteArray()
    val y = b.toByteArray()
    (x zip y).map { (p, q) -> p.toUByte().compareTo(q.toUByte()) }.firstOrNull { it != 0 } ?: x.size.compareTo(y.size)
}

private fun sha256(data: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(Locale.ROOT, it) }
