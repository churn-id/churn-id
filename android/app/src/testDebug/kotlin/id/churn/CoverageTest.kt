package id.churn

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val header = block {
        writeByte(0x01)
        writeChar(0xC0C0)
        writeChar(0x1007)
    }

    private val session = block {
        writeByte(0x10)
        writeUTF("localhost-6a1f03c2")
        writeLong(1_795_000_000_000)
        writeLong(1_795_000_060_000)
    }

    private val run = block {
        writeByte(0x11)
        writeLong(0x5EED)
        writeUTF("id/churn/Imei")
        writeByte(3)
        writeByte(0b101)
    }

    @Test
    fun leavesOutTheSessionInfo() {
        assertArrayEquals(header + run, Coverage.withoutSession(header + session + run))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesDataWithoutSessionInfo() {
        Coverage.withoutSession(header + run)
    }

    @Test
    fun keepsToOneFile() {
        val file = Coverage.file(folder.root).apply { writeBytes(header + run) }

        assertEquals(file, Coverage.file(folder.root))
    }

    @Test
    fun addsALineOfBase64ForEachSaveThatCounted() {
        Coverage.append(folder.root, header + run)
        Coverage.append(folder.root, header)
        Coverage.append(folder.root, header + run)

        val lines = Coverage.file(folder.root).readLines()
        assertEquals(2, lines.size)
        lines.forEach { assertArrayEquals(header + run, Base64.getDecoder().decode(it)) }
    }

    /** With `-Pcoverage`, the unit tests run with JaCoCo's agent, as the coverage build does (build.gradle.kts). */
    @Test
    fun findsTheAgentOnlyWithCoverage() {
        assertEquals(System.getProperty("coverage") != null, Coverage.AVAILABLE)
    }

    @Test
    fun takesTheAgentsCounts() {
        assumeTrue(Coverage.AVAILABLE)

        // Without a reset, so that the unit tests' own counts stay whole.
        val data = Coverage.take(reset = false)

        assertArrayEquals(header, data.copyOf(header.size))
        assertEquals(run[0], data[header.size])
        assertTrue(String(data, Charsets.ISO_8859_1).contains("id/churn/Coverage"))
    }

    private fun block(write: DataOutputStream.() -> Unit) =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(write).flush() }.toByteArray()
}
