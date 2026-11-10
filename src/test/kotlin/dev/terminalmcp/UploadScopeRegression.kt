package dev.terminalmcp

import java.nio.file.Files

object UploadScopeRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val temp = Files.createTempDirectory("mcp-upload-scope-")
        val root = Files.createDirectory(temp.resolve("project"))
        val child = Files.createDirectory(root.resolve("configs"))
        val file = Files.createFile(child.resolve("example.yaml"))
        var cases = 0
        fun rejected(raw: String?, full: Boolean = false) {
            val failure = runCatching { UploadScope.resolve(root, raw, full) }.exceptionOrNull()
            check(failure is RemoteTransferExpectedFailureException) { "Unexpected result for path: " + raw }
            cases++
        }
        try {
            rejected(null)
            rejected("")
            rejected("  ")
            rejected(null, true)
            rejected(".")
            rejected(root.toString())
            rejected("configs/..")
            rejected("..")
            check(UploadScope.resolve(root, ".", true) == root.toRealPath()); cases++
            check(UploadScope.resolve(root, "configs", false) == child.toRealPath()); cases++
            check(UploadScope.resolve(root, "configs/example.yaml", false) == file.toRealPath()); cases++
            if (System.getProperty("os.name").startsWith("Windows")) {
                rejected(root.toString().uppercase().replace('\\', '/'))
                rejected(root.toString().lowercase())
            }
            val link = temp.resolve("alias")
            if (runCatching { Files.createSymbolicLink(link, root) }.isSuccess) {
                try { rejected(link.toString()) } finally { Files.delete(link) }
            }
        } finally {
            Files.delete(file)
            Files.delete(child)
            Files.delete(root)
            Files.delete(temp)
        }
        println("Upload scope regression: " + cases + " cases passed")
    }
}
