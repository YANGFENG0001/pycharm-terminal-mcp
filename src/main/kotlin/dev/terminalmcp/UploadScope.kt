package dev.terminalmcp

import java.nio.file.Files
import java.nio.file.Path

internal object UploadScope {
    fun resolve(root: Path, raw: String?, allowFullProject: Boolean): Path {
        if (raw.isNullOrBlank()) throw RemoteTransferExpectedFailureException(
            "上传/同步必须明确指定 local_path；不会默认上传项目。部分文件请使用 upload_selected_files_to_deployment_server；全项目请使用 upload_entire_project_to_deployment_server 并声明 allow_full_project=true。",
        )
        val base = root.toRealPath()
        val path = base.resolve(raw.trim()).normalize().toRealPath()
        if (!allowFullProject && (base.startsWith(path) || Files.isSameFile(base, path))) {
            throw RemoteTransferExpectedFailureException(
                "路径覆盖项目根目录；全项目上传/同步必须显式 allow_full_project=true。",
            )
        }
        return path
    }
}
