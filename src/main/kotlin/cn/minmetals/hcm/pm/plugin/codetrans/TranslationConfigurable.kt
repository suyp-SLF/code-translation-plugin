package cn.minmetals.hcm.pm.plugin.codetrans

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.io.File

/**
 * Settings → Tools → Code Translation 配置页。
 */
class TranslationConfigurable(private val project: Project) : BoundConfigurable("Code Translation") {

    private val settings: TranslationSettings = TranslationSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        group("源码路径") {
            row("源目录（只读）:") {
                textFieldWithBrowseButton(
                    browseDialogTitle = "Select Source (Read-Only) Directory",
                    project = project,
                    fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                )
                    .columns(40)
                    .bindText(settings::sourcePath)
            }
            row("目标目录（可写）:") {
                textFieldWithBrowseButton(
                    browseDialogTitle = "Select Target (Writable) Directory",
                    project = project,
                    fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                )
                    .columns(40)
                    .bindText(settings::targetPath)
            }
        }

        group("扫描规则") {
            row("文件后缀（逗号分隔）:") {
                textField()
                    .columns(40)
                    .bindText(settings::fileExtensions)
            }
            row("最大文件数:") {
                intTextField(0..99999)
                    .columns(6)
                    .bindIntText(settings::maxFiles)
            }
            row("忽略目录（逗号分隔）:") {
                textField()
                    .columns(40)
                    .bindText(settings::ignoredDirectories)
            }
            row("忽略文件名 glob（逗号分隔，可空）:") {
                textField()
                    .columns(40)
                    .bindText(settings::ignoredFilePatterns)
                    .comment("例如：*Test.java,*.class")
            }
        }
    }

    override fun apply() {
        super.apply()
        validatePathSafety()
    }

    private fun validatePathSafety() {
        val src = settings.sourcePath.trim()
        val dst = settings.targetPath.trim()
        if (src.isBlank() || dst.isBlank()) return

        val srcCanon = runCatching { File(src).canonicalPath }.getOrDefault(src)
        val dstCanon = runCatching { File(dst).canonicalPath }.getOrDefault(dst)

        when {
            srcCanon == dstCanon -> {
                Messages.showWarningDialog(
                    project,
                    "源目录与目标目录相同，扫描将比较文件自身。请检查配置。",
                    "Code Translation"
                )
            }
            dstCanon.startsWith(srcCanon + File.separator) -> {
                Messages.showWarningDialog(
                    project,
                    "目标目录位于源目录内部，可能造成递归扫描。请检查配置。",
                    "Code Translation"
                )
            }
            srcCanon.startsWith(dstCanon + File.separator) -> {
                Messages.showWarningDialog(
                    project,
                    "源目录位于目标目录内部，可能造成递归扫描。请检查配置。",
                    "Code Translation"
                )
            }
        }
    }
}
