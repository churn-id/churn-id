package id.churn

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The debug log, which the user can have Churn keep so that a problem can be looked into afterwards (design.md,
 * section 7). While it is [on], it records every command the app sends to the router with the router's answer, what
 * each step reports, why a job or step stopped, and the lines GL's eSIM manager logs during steps 1 and 3. Each job
 * or step starts a section, and times count in seconds from its start. Numbers of 12 digits or more, such as ICCIDs,
 * IMSIs and IMEIs, stay out, and so do the answers to [Shell.runPrivately], such as files' contents and the time of
 * day, so that the log ties no cards to each other or to times. It keeps the last [SWAPS] swaps.
 *
 * [start], [watchEsim] and [copyEsim] run on the thread that runs the router's jobs.
 */
class DebugLog(
    private val file: File,
    private val on: () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** When the current section started, in milliseconds of [now]. */
    private var started = now()

    /** GL's eSIM manager's log, while a step watches it. */
    private var esim: EsimWatch? = null

    /**
     * The router whose eSIM manager's log is watched, where the next copy starts, how the log began then, and the
     * router's clock at [at] seconds into the section.
     */
    private class EsimWatch(val router: Router, var from: Long, var head: String, val clock: Long, val at: Double)

    val isEmpty get() = file.length() == 0L

    /** Marks the start of a swap. */
    @Synchronized
    fun beginSwap() {
        if (on()) file.appendText("\n$SWAP\n")
    }

    /** Starts a section for the job or step [title]. */
    @Synchronized
    fun start(title: String) {
        started = now()
        esim = null
        if (!on()) return
        trim()
        file.appendText("\n== $title\n")
    }

    /** Adds [text] at [at] seconds into the section, by default now. */
    @Synchronized
    fun add(text: String, at: Double = seconds()) {
        if (!on()) return
        val lines = mask(text).trimEnd().lines()
        val first = "%7.1f %s\n".format(Locale.ROOT, at, lines[0])
        file.appendText(first + lines.drop(1).joinToString("") { "        $it\n" })
    }

    /** Adds why a job or step stopped: [message], as the screen shows it, and where in the app [e] was thrown. */
    fun error(message: String, e: Exception) {
        add("! $message")
        add("  $e")
        e.stackTrace.filter { it.className.startsWith("id.churn.") }.take(6).forEach { add("    at $it") }
    }

    /** [shell], with every command and its answer added to the log while it is on. */
    fun record(shell: Shell) = object : Shell {
        override fun run(command: String, input: ByteArray?, timeoutSeconds: Long) =
            record(command, input, answer = true) { shell.run(command, input, timeoutSeconds) }

        override fun runPrivately(command: String, input: ByteArray?, timeoutSeconds: Long) =
            record(command, input, answer = false) { shell.runPrivately(command, input, timeoutSeconds) }
    }

    /** Runs [command] with [run], and adds it, the size of its [input] and, with [answer], its answer to the log. */
    private fun record(command: String, input: ByteArray?, answer: Boolean, run: () -> Output): Output {
        if (!on()) return run()
        // A script's first line tells which script it is. Masking comes before a cut, which could split a number.
        val lines = command.lines()
        val first = mask(lines[0])
        val shown = if (lines.size == 1 && first.length <= MAX_COMMAND) first else first.take(MAX_COMMAND) + " …"
        add("$ $shown" + (input?.let { " < ${it.size} bytes" } ?: ""))
        val output = try {
            run()
        } catch (e: Exception) {
            add("  failed: $e")
            throw e
        }
        val status = output.status?.let { "exit $it" } ?: "no exit status"
        if (!answer) {
            add("  $status, answer of ${output.stdout.size} bytes left out")
        } else {
            val lines = output.text.lines().map { "| ${it.trim()}" } + output.stderr.lines().map { "! ${it.trim()}" }
            val text = mask(lines.filter { it.length > 2 }.joinToString("\n"))
            add("  $status\n" + if (text.length > MAX_ANSWER) text.take(MAX_ANSWER) + " …" else text)
        }
        return output
    }

    /** Starts watching GL's eSIM manager's log on [router] for the current step, from where the log ends now. */
    fun watchEsim(router: Router) {
        if (!on()) return
        try {
            val log = router.esimLog(from = 0, head = "", max = 0)
            esim = EsimWatch(router, from = log.size, head = log.head, clock = log.clock, at = seconds())
        } catch (e: Exception) {
            add("  can't read GL's eSIM manager's log: $e")
        }
    }

    /**
     * Adds the lines GL's eSIM manager has logged since the watch started or since the last copy, each at its time in
     * the section. GL's eSIM manager logs its times in UTC (inferred).
     */
    fun copyEsim() {
        val watch = esim ?: return
        if (!on()) return
        val log = try {
            watch.router.esimLog(watch.from, watch.head, MAX_ESIM)
        } catch (e: Exception) {
            add("  can't read GL's eSIM manager's log: $e")
            return
        }
        watch.from = log.size
        watch.head = log.head
        val lines = log.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        add(if (lines.isEmpty()) "GL's eSIM manager logged nothing new." else "GL's eSIM manager logged:")
        var at = watch.at
        for (line in lines) {
            val time = ESIM_LINE.matchEntire(line)
            if (time != null) {
                val (month, day, hour, minute, second, year) = time.destructured
                val clock = LocalDateTime.of(
                    year.toInt(),
                    MONTHS.indexOf(month) + 1,
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                    second.toInt(),
                ).toEpochSecond(ZoneOffset.UTC)
                at = watch.at + (clock - watch.clock)
            }
            add("· " + (time?.groupValues?.last() ?: line), at)
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
    }

    private fun seconds() = (now() - started) / 1000.0

    /** Drops the oldest sections, keeping the last [SWAPS] swaps and what came after them, at most [MAX_CHARS]. */
    private fun trim() {
        if (!file.exists()) return
        val text = file.readText()
        val sections = Regex("^== ", RegexOption.MULTILINE).findAll(text).map { it.range.first }.toList()
        val swaps = sections.filter { text.startsWith("$SWAP\n", it) }
        var from = swaps.getOrElse(swaps.size - SWAPS) { 0 }
        if (text.length - from > MAX_CHARS) from = sections.firstOrNull { it >= text.length - MAX_CHARS } ?: text.length
        if (from > 0) {
            val kept = File(file.path + ".new")
            kept.writeText(text.substring(from))
            kept.renameTo(file)
        }
    }

    companion object {
        const val FILE_NAME = "debug-log.txt"

        /** The swaps the log keeps. */
        const val SWAPS = 3

        private const val SWAP = "== New swap"
        private const val MAX_CHARS = 1_000_000
        private const val MAX_COMMAND = 300
        private const val MAX_ANSWER = 2_000
        private const val MAX_ESIM = 100_000

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** A line of GL's eSIM manager's log, which starts with its time, such as `Sun Oct 11 11:17:08 2026`. */
        private val ESIM_LINE = Regex(
            """[A-Z][a-z]{2} (${MONTHS.joinToString("|")}) +(\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\d{4}) (.*)""",
        )

        private val LONG_NUMBER = Regex("[0-9A-Fa-f]{12,}")

        /**
         * Numbers that name no card, which the log shows: the ICCIDs, and the IMSIs, of the built-in eSIM and of a
         * 9eSIM adapter card without an enabled profile.
         */
        private val NO_CARD = Router.PLACEHOLDERS + setOf("001010123456063", "111111111111111")

        /** [text] without numbers of 12 digits or more, such as ICCIDs, IMSIs and IMEIs, but those that name no card. */
        fun mask(text: String) = LONG_NUMBER.replace(text) { if (it.value in NO_CARD) it.value else "…" }
    }
}
