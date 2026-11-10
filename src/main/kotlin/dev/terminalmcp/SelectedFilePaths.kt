package dev.terminalmcp

import java.nio.file.Files
import java.nio.file.Path

internal object SelectedFilePaths {
    fun resolve(projectRoot: Path, localPaths: List<String>): List<Path> {
        require(localPaths.isNotEmpty()) { "必须明确指定至少一个文件；空列表不会上传整个项目。" }
        val root = projectRoot.toRealPath()
        return localPaths.map { raw ->
            require(raw.isNotBlank()) { "文件路径不能为空。" }
            val path = root.resolve(raw).normalize().toRealPath()
            require(path.startsWith(root) && Files.isRegularFile(path)) {
                "部分上传只接受项目内的普通文件，不接受目录或项目外路径：$raw"
            }
            path
        }.distinct()
    }
}
