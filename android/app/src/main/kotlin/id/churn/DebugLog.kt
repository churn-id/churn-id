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
 * [watchEsim], [copyEsim] and [stopWatchingEsim] run on the thread that runs the router's jobs.
 */
class DebugLog(
    private val file: File,
    private val on: () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** When the current section started, in milliseconds of [now]. */
    private var started = now()

    /**
     * The stretch of GL's eSIM manager's log that the next copy takes, while a step watches the log: from [watchEsim]
     * to [stopWatchingEsim].
     */
    private var esim: EsimWatch? = null

    /**
     * A stretch of GL's eSIM manager's log on [router], from the read where it begins to the copy that takes it: where
     * it starts and how the log began then, and, the same for the whole step, the router's clock at [at] seconds into
     * the section.
     */
    private data class EsimWatch(
        val router: Router,
        val from: Long,
        val head: String,
        val clock: Long,
        val at: Double,
    ) {

        /**
         * Reads the stretch, at most its last [MAX] bytes from the start of a line: its lines, each with its time in
         * seconds into the section, and the watch for the stretch that follows. GL's eSIM manager logs its times in UTC
         * (inferred).
         */
        fun read(): Pair<List<Pair<Double, String>>, EsimWatch> {
            val log = router.esimLog(from, head, MAX)
            var time = at
            val lines = log.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val match = LINE.matchEntire(line)
                if (match != null) time = at + (clockOf(match) - clock)
                time to (match?.groupValues?.last() ?: line)
            }
            return lines to copy(from = log.size, head = log.head)
        }

        companion object {
            private const val MAX = 100_000

            private val MONTHS =
                listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

            /** A line of GL's eSIM manager's log, which starts with its time, such as `Sun Oct 11 11:17:08 2026`. */
            private val LINE = Regex(
                """[A-Z][a-z]{2} (${MONTHS.joinToString("|")}) +(\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\d{4}) (.*)""",
            )

            /** The time that [line] starts with, in seconds since 1970. */
            private fun clockOf(line: MatchResult): Long {
                val (month, day, hour, minute, second, year) = line.destructured
                return LocalDateTime.of(
                    year.toInt(),
                    MONTHS.indexOf(month) + 1,
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                    second.toInt(),
                ).toEpochSecond(ZoneOffset.UTC)
            }
        }
    }

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
     * the section.
     */
    fun copyEsim() {
        val watch = esim ?: return
        if (!on()) return
        val (lines, next) = try {
            watch.read()
        } catch (e: Exception) {
            add("  can't read GL's eSIM manager's log: $e")
            return
        }
        esim = next
        add(if (lines.isEmpty()) "GL's eSIM manager logged nothing new." else "GL's eSIM manager logged:")
        for ((at, line) in lines) add("· $line", at)
    }

    /** Makes a last copy, as [copyEsim] does, and stops watching GL's eSIM manager's log. */
    fun stopWatchingEsim() {
        copyEsim()
        esim = null
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

        private val LONG_NUMBER = Regex("[0-9A-Fa-f]{12,}")

        /**
         * The numbers that name no card, and the names the log shows in their place: the ICCID and IMSI that the
         * built-in eSIM shows without an enabled profile.
         */
        private val NO_CARD = mapOf(
            Router.PLACEHOLDER_ICCID to "[built-in eSIM placeholder ICCID]",
            "001010123456063" to "[built-in eSIM test IMSI]",
        )

        /**
         * [text] without numbers of 12 digits or more, such as ICCIDs, IMSIs and IMEIs. Those that name no card show
         * by name.
         */
        fun mask(text: String) = LONG_NUMBER.replace(text) { NO_CARD[it.value] ?: "…" }
    }
}
