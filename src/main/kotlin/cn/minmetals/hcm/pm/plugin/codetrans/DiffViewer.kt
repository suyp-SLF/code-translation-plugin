package cn.minmetals.hcm.pm.plugin.codetrans

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * 打开源（只读）↔ 目标（可编辑）的 Diff 视图。
 *
 * 当目标文件不存在（ONLY_SOURCE）：先在磁盘上创建一个空文件，再走正常 Diff 流程。
 * 这样右侧编辑保存可以正常落盘，不会出现"改了不见了"的情况。
 */
object DiffViewer {

    fun openDiff(project: Project, entry: FileDiffEntry) {
        val settings = TranslationSettings.getInstance(project)
        val contentFactory = DiffContentFactory.getInstance()

        // 左侧：源文件（只读）
        val srcFile = entry.sourceFile
        val leftContent: DiffContent = when {
            srcFile == null -> contentFactory.create("// 仅目标：源目录不存在该文件")
            !srcFile.exists() -> contentFactory.create("// 源文件不存在")
            else -> {
                val srcVf = findOrRefresh(srcFile)
                if (srcVf != null) {
                    val c = contentFactory.create(project, srcVf)
                    (c as? DocumentContent)?.let { dc ->
                        WriteAction.runAndWait<Throwable> { dc.document.setReadOnly(true) }
                    }
                    c
                } else {
                    contentFactory.create("// 无法读取源文件: ${srcFile.absolutePath}")
                }
            }
        }

        // 右侧：目标文件（可编辑）。若不存在则在磁盘创建空文件后再打开。
        val targetFile = entry.targetFile ?: File(settings.targetPath, entry.relativePath)
        if (!targetFile.exists()) {
            if (!ensureEmptyFileCreated(project, targetFile)) return
        }
        val destVf = findOrRefresh(targetFile)
        val rightContent: DiffContent = if (destVf != null) {
            contentFactory.create(project, destVf)
        } else {
            contentFactory.create("// 无法读取目标文件: ${targetFile.absolutePath}")
        }

        val title = "${entry.status.label}: ${entry.relativePath}"
        val leftTitle = if (entry.sourceFile?.exists() == true)
            "源文件（只读）: ${entry.relativePath}"
        else
            "源文件（不存在）: ${entry.relativePath}"
        val rightTitle = if (entry.targetFile?.exists() == true)
            "目标文件（可写）: ${entry.relativePath}"
        else
            "目标文件（新建，可直接编辑保存）: ${entry.relativePath}"

        val request = SimpleDiffRequest(title, leftContent, rightContent, leftTitle, rightTitle)
        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * 仅源场景：先在磁盘建一个空目标文件并刷新 VFS。
     * 返回是否成功；失败时向用户提示。
     */
    private fun ensureEmptyFileCreated(project: Project, file: File): Boolean {
        return try {
            WriteAction.computeAndWait<Boolean, Exception> {
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                true
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "无法创建目标文件：${file.absolutePath}\n${e.message}",
                "Code Translation"
            )
            false
        }
    }

    private fun findOrRefresh(file: File): VirtualFile? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return null
        vf.refresh(false, false)
        return vf
    }
}
