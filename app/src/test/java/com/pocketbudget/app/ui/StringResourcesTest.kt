package com.pocketbudget.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every formatted string resource must format with the argument types the app passes
 * (%n$s text, %n$d whole numbers). A malformed placeholder only fails at runtime, when
 * the screen showing it is drawn, so it is checked here instead.
 */
class StringResourcesTest {

    private val placeholder = Regex("""%(\d+)\$([a-zA-Z])""")

    @Test
    fun allFormatPlaceholdersAreWellFormedAndFormat() {
        // Unit tests run with the module directory as the working directory.
        val file = File("src/main/res/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val texts = buildList {
            val strings = document.getElementsByTagName("string")
            for (i in 0 until strings.length) add(strings.item(i).attributes.getNamedItem("name").nodeValue to strings.item(i).textContent)
            val items = document.getElementsByTagName("item")
            for (i in 0 until items.length) add("plural item" to items.item(i).textContent)
        }
        assertTrue("strings.xml was read", texts.size > 100)
        val problems = texts.mapNotNull { (name, text) ->
            val specs = placeholder.findAll(text).toList()
            val wrongType = specs.filter { it.groupValues[2] !in setOf("s", "d") }
            // Any other % must be literal: "%%", or followed by a space, ")" or the end.
            val stray = Regex("""%(?!\d+\$[sd]|%|\s|\)|$)""").containsMatchIn(text)
            val args = specs.sortedBy { it.groupValues[1].toInt() }.distinctBy { it.groupValues[1] }
                .map { if (it.groupValues[2] == "d") 1 as Any else "x" }
            val formats = runCatching { if (specs.isNotEmpty()) String.format(text.replace("\\'", "'"), *args.toTypedArray()) }.isSuccess
            if (wrongType.isNotEmpty() || stray || !formats) "$name: $text" else null
        }
        assertTrue("Malformed format strings:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
