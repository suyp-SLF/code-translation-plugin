//                                                                             ╔══════════════════════════════╗
//  CodeTranslationToolWindowFactory                                            ║  IDEA 插件 · 代码平移助手  ║
//  工具窗口入口：注册 "Code Translation" 面板                                    ╚══════════════════════════════╝
//  ────────────────────────────────────────────────────────────────────────────────────────────────────
//  功能：列出源目录（只读）和目标目录（可写）中同名文件的差异状态，
//        支持双击打开 Diff 视图、单文件拷贝、刷新文件列表。
//  ====================================================================================================

package cn.minmetals.hcm.pm.plugin.codetrans

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * IDEA ToolWindow 工厂：在右侧面板注册 "Code Translation" 窗口。
 */
class CodeTranslationToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TranslationToolWindowPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel.mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
