package dev.terminalmcp

import com.intellij.mcpserver.impl.McpSessionProjectResolver
import com.intellij.mcpserver.impl.McpSessionProjectResolverImpl
import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the explicitly opted-in connection uses focus; all other clients retain IDE routing. */
class CurrentProjectResolver : McpSessionProjectResolver {
    private val delegate = McpSessionProjectResolverImpl()

    override suspend fun resolveSessionProject(
        projectPathFromArgument: String?,
        projectPathFromCallHeader: String?,
        projectPathFromSessionHeader: String?,
        roots: Set<String>,
    ): Project {
        // Fixed-project discovery also uses the EDT; reject before it can queue behind a dialog.
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                CurrentProjectRouting.checkModal(LaterInvocator.isInModalContext())
            } catch (ex: ExpectedMcpFailureException) {
                throw McpExpectedError(ex.message ?: "请先关闭 PyCharm 对话框。")
            }
        }
        if (!CurrentProjectRouting.followsCurrent(projectPathFromArgument, projectPathFromCallHeader, projectPathFromSessionHeader)) {
            return delegate.resolveSessionProject(projectPathFromArgument, projectPathFromCallHeader, projectPathFromSessionHeader, roots)
        }
        // Read routing state even while a dialog is open, but never queue work behind that dialog.
        return withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                CurrentProjectRouting.checkModal(LaterInvocator.isInModalContext())
                val open = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed || it.isDefault }
                val project = CurrentProjectRouting.choose(open, IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project)
                CurrentProjectRouting.checkExplicitPaths(project.basePath, projectPathFromArgument, projectPathFromCallHeader,
                    projectPathFromSessionHeader)
                project
            } catch (ex: ExpectedMcpFailureException) {
                throw McpExpectedError(ex.message ?: "无法确定当前项目。")
            }
        }
    }
}
