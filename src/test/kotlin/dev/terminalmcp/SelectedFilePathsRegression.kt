package dev.terminalmcp

import java.nio.file.Files
import java.util.Comparator

object SelectedFilePathsRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val temporary = Files.createTempDirectory("mcp-selection-test-")
        try {
            val root = Files.createDirectory(temporary.resolve("project"))
            val first = Files.writeString(root.resolve("first.txt"), "first")
            val second = Files.writeString(root.resolve("second.txt"), "second")
            Files.writeString(temporary.resolve("outside.txt"), "outside")
            check(SelectedFilePaths.resolve(root, listOf("first.txt", second.toString())) == listOf(first.toRealPath(), second.toRealPath()))
            check(SelectedFilePaths.resolve(root, listOf("first.txt", "./first.txt")) == listOf(first.toRealPath()))
            val invalid = listOf(emptyList(), listOf(""), listOf(" "), listOf("."),
                listOf("../outside.txt"), listOf("missing.txt"), listOf("first.txt", "missing.txt"))
            invalid.forEach { selection ->
                check(runCatching { SelectedFilePaths.resolve(root, selection) }.isFailure) { "Accepted $selection" }
            }
            println("Selected-file path regression: 9 cases passed")
        } finally {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
