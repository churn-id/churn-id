package id.churn

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.File
import java.util.Base64
import kotlin.random.Random

/**
 * Which of the app's code the coverage build has run, for the coverage pass of the release tests (release-tests.md,
 * "Coverage pass"). Built with `-Pcoverage`, a debug build counts it in JaCoCo's counters, in the app's process, which
 * Android can end. So [save] adds the counts to a file among the app's private files whenever the app goes to the
 * background or a step that ran in the background ends, and before the user shares the file. Like the debug log, the
 * file is plain text, so that the apps that take the debug log take it too, and leaves out the time of day. Other debug
 * builds count nothing, and their menu has no Share coverage.
 */
object Coverage {

    /** JaCoCo's agent in the coverage build, reached by reflection, so that the other builds build without it. */
    private val agent: Any? = try {
        Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null)
    } catch (e: ReflectiveOperationException) {
        null
    }

    val AVAILABLE = agent != null

    /** Adds the counts since the last call to the file. Returns why that failed rather than throwing, for onStop. */
    fun save(context: Context): Exception? {
        if (agent == null) return null
        return try {
            append(context.filesDir, take(reset = true))
            null
        } catch (e: Exception) {
            e
        }
    }

    /** Adds [data] to the file in [dir] as a line of Base64, which coverageReport turns back into the data. */
    internal fun append(dir: File, data: ByteArray) {
        if (data.size > HEADER_SIZE) file(dir).appendText(Base64.getEncoder().encodeToString(data) + "\n")
    }

    /** Where the app that the user shares the file with reads it, under the file's name. */
    fun uri(context: Context): Uri = Uri.parse("content://${CoverageProvider.AUTHORITY}/${file(context.filesDir).name}")

    /**
     * The file in [dir] that holds the counts, or the one to start. Its name is random, so that the files of different
     * installs, each shared before the uninstall that deletes it, don't take each other's place.
     */
    internal fun file(dir: File): File = dir.listFiles { file -> NAME.matches(file.name) }?.firstOrNull()
        ?: File(dir, "churn-coverage-%08x.txt".format(Random.nextInt()))

    /** The counts since the last call that reset them, as JaCoCo's execution data without its session info. */
    internal fun take(reset: Boolean): ByteArray {
        val data = agent!!.javaClass.getMethod("getExecutionData", Boolean::class.java).invoke(agent, reset)
        return withoutSession(data as ByteArray)
    }

    /**
     * JaCoCo's execution data [data] without its session info, which names the host and holds the times at which the
     * counts began and were taken. The agent writes the header first, then the session info, then a block for each
     * class that ran.
     */
    internal fun withoutSession(data: ByteArray): ByteArray {
        val input = DataInputStream(data.inputStream())
        check(input.readByte() == HEADER && input.readChar() == MAGIC_NUMBER) { "Not JaCoCo's execution data" }
        input.readChar()
        check(input.readByte() == SESSION_INFO) { "No session info after the header" }
        input.readUTF()
        input.readLong()
        input.readLong()
        return data.copyOfRange(0, HEADER_SIZE) + data.copyOfRange(data.size - input.available(), data.size)
    }

    private val NAME = Regex("""churn-coverage-\p{XDigit}{8}\.txt""")

    // From JaCoCo's ExecutionDataWriter: the header is its block type, the magic number and the format's version.
    private const val HEADER: Byte = 0x01
    private const val MAGIC_NUMBER = '\uC0C0'
    private const val HEADER_SIZE = 5
    private const val SESSION_INFO: Byte = 0x10
}
