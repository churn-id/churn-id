package id.churn

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Keeps the usage rules in the same words in the three places that show them: the app's strings, README.md's "Rules",
 * and design.md, section 8, where a reason follows each rule.
 */
class RulesTest {

    @Test
    fun readmeShowsTheAppsRules() = assertAppsWording("README.md", markdownSection(File("../../README.md"), "## Rules"))

    @Test
    fun designDocumentShowsTheAppsRules() =
        assertAppsWording("design.md", markdownSection(File("../../docs/design.md"), "## 8. Usage rules"))

    /** Checks that the paragraphs and list items [blocks] of the document [name] hold the rules in the app's words. */
    private fun assertAppsWording(name: String, blocks: List<String>) {
        // The two paragraphs that introduce the rules, among any others the document has.
        for (key in listOf("rules_intro", "rules_card_kinds")) {
            assertTrue("$name has no paragraph $key: ${STRINGS[key]}", STRINGS[key] in blocks)
        }
        val always = ARRAYS.getValue("rules_always")
        val rules = always + ARRAYS.getValue("rules_where_you_can")
        val items = blocks.filter { LIST_ITEM.matches(it) }
        assertEquals("The rules in $name", rules.size, items.size)
        rules.forEachIndexed { i, rule -> assertEquals("Rule ${i + 1} in $name", "${i + 1}. $rule", items[i]) }
        // Each heading comes right before the first rule it introduces.
        for ((key, first) in mapOf("rules_always_heading" to 0, "rules_where_you_can_heading" to always.size)) {
            val before = blocks[blocks.indexOf(items[first]) - 1]
            assertEquals("The heading before rule ${first + 1} in $name", STRINGS[key], before)
        }
    }

    private companion object {
        val LIST_ITEM = Regex("[0-9]+\\. .*")

        /** The app's strings and string arrays, as Android reads them. */
        val RESOURCES: Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).documentElement
        val STRINGS = elements(RESOURCES, "string").associate { it.getAttribute("name") to text(it) }
        val ARRAYS = elements(RESOURCES, "string-array")
            .associate { array -> array.getAttribute("name") to elements(array, "item").map(::text) }

        fun elements(parent: Element, tag: String) =
            parent.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

        /** A resource's text with its escapes undone, such as `\'`, and its whitespace collapsed, as Android does. */
        fun text(element: Element) = Regex("\\\\(.)").replace(element.textContent) {
            when (val escaped = it.groupValues[1]) {
                "n" -> "\n"
                "t" -> "\t"
                else -> escaped
            }
        }.let(::collapsed)

        fun collapsed(text: String) = text.trim().replace(Regex("\\s+"), " ")

        /**
         * The paragraphs and list items of the Markdown [file] under [heading], up to the next heading of its level or
         * higher, each on one line: those that start at the margin, without indented ones, such as design.md's
         * reasons, and without HTML comments.
         */
        fun markdownSection(file: File, heading: String): List<String> {
            val lines = file.readLines()
            val start = lines.indexOf(heading)
            assertTrue("${file.name} has no heading $heading", start >= 0)
            val level = heading.takeWhile { it == '#' }.length
            val end = (start + 1 until lines.size).firstOrNull { lines[it].matches(Regex("#{1,$level} .*")) }
            val blocks = mutableListOf<String>()
            var block: StringBuilder? = null
            var newBlock = true
            for (line in lines.subList(start + 1, end ?: lines.size)) {
                if (line.isBlank() || line.trimStart().startsWith("<!--")) {
                    block?.let { blocks += collapsed(it.toString()) }
                    block = null
                    newBlock = true
                    continue
                }
                val atMargin = !line.startsWith(" ")
                // A list item starts a block of its own, also right below the one before it.
                if (atMargin && (newBlock || LIST_ITEM.matches(line))) {
                    block?.let { blocks += collapsed(it.toString()) }
                    block = StringBuilder(line)
                } else if (newBlock) {
                    // An indented block, which belongs to the list item above it.
                    block?.let { blocks += collapsed(it.toString()) }
                    block = null
                } else {
                    block?.append(' ')?.append(line)
                }
                newBlock = false
            }
            block?.let { blocks += collapsed(it.toString()) }
            return blocks
        }
    }
}
