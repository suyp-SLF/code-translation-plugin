package cn.minmetals.hcm.pm.plugin.codetrans

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * 工具窗口主面板。
 *
 * 顶部：状态统计 + 搜索框 + 过滤 + 工具按钮
 * 中部：文件列表（多选、可排序、右键菜单）
 * 底部：操作提示
 */
class TranslationToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) {
    private val settings = TranslationSettings.getInstance(project)

    @Volatile
    private var entries: List<FileDiffEntry> = emptyList()

    val mainPanel = JPanel(BorderLayout())

    private val statusLabel = JLabel("请先配置源/目标目录 → Settings → Tools → Code Translation")
    private val tableModel = object : DefaultTableModel(
        arrayOf<Any>("文件名", "状态", "源大小", "目标大小"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val fileTable = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        autoCreateRowSorter = true
        columnModel.getColumn(0).preferredWidth = 360
        columnModel.getColumn(1).preferredWidth = 70
        columnModel.getColumn(2).preferredWidth = 80
        columnModel.getColumn(3).preferredWidth = 80
        columnModel.getColumn(1).cellRenderer = StatusCellRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openDiffForSelectedRow()
                }
            }
        })
        selectionModel.addListSelectionListener { updateStatusLabel() }
    }

    private val filterCombo = javax.swing.JComboBox(arrayOf("全部", "仅源", "仅目标", "有差异", "一致"))
    private val searchField = SearchTextField()
    private val refreshButton = JButton("刷新", AllIcons.Actions.Refresh)
    private val configureButton = JButton("配置", AllIcons.General.Settings)
    private val batchCopyButton = JButton("拷贝选中", AllIcons.Actions.Copy)

    init {
        buildUI()
        bindActions()
        installPopupMenu()
        registerFileSaveListener()
    }

    private fun buildUI() {
        val topPanel = JPanel(BorderLayout())
        topPanel.add(statusLabel, BorderLayout.WEST)

        val toolbarPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
        toolbarPanel.add(JLabel("搜索:"))
        searchField.preferredSize = Dimension(160, 28)
        toolbarPanel.add(searchField)
        toolbarPanel.add(JLabel("过滤:"))
        filterCombo.preferredSize = Dimension(90, 28)
        toolbarPanel.add(filterCombo)
        toolbarPanel.add(batchCopyButton)
        toolbarPanel.add(refreshButton)
        toolbarPanel.add(configureButton)
        topPanel.add(toolbarPanel, BorderLayout.EAST)

        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(
            JBScrollPane(fileTable).apply { preferredSize = Dimension(700, 400) },
            BorderLayout.CENTER
        )

        val tipLabel = JLabel("  双击=Diff | 右键=更多操作 | Ctrl/Shift+点击=多选")
        tipLabel.foreground = JBColor.GRAY
        mainPanel.add(tipLabel, BorderLayout.SOUTH)
    }

    private fun bindActions() {
        refreshButton.addActionListener { refreshFileList() }
        configureButton.addActionListener {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, TranslationConfigurable::class.java)
        }
        filterCombo.addActionListener { reloadTable() }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = reloadTable()
        })
        batchCopyButton.addActionListener { copySelectedRows() }
    }

    private fun installPopupMenu() {
        val popup = JPopupMenu().apply {
            add(JMenuItem("打开 Diff", AllIcons.Actions.Diff).apply {
                addActionListener { openDiffForSelectedRow() }
            })
            add(JMenuItem("拷贝到目标", AllIcons.Actions.Copy).apply {
                addActionListener { copySelectedRows() }
            })
            addSeparator()
            add(JMenuItem("在文件管理器中显示", AllIcons.Actions.MenuOpen).apply {
                addActionListener { revealInExplorer() }
            })
            add(JMenuItem("复制相对路径").apply {
                addActionListener { copyPath(absolute = false) }
            })
            add(JMenuItem("复制源文件绝对路径").apply {
                addActionListener { copyPath(absolute = true) }
            })
        }
        fileTable.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component?, x: Int, y: Int) {
                val row = fileTable.rowAtPoint(Point(x, y))
                if (row >= 0 && !fileTable.isRowSelected(row)) {
                    fileTable.setRowSelectionInterval(row, row)
                }
                popup.show(comp, x, y)
            }
        })
    }

    /** 扫描后端，挂到 IDEA 进度条，可取消。 */
    fun refreshFileList() {
        if (settings.sourcePath.isBlank() || settings.targetPath.isBlank()) {
            statusLabel.text = "请先配置源/目标目录：Settings → Tools → Code Translation"
            return
        }
        val keyword = searchField.text.trim()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "扫描代码差异", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = FileScanService.scan(settings, indicator, keyword)
                ApplicationManager.getApplication().invokeLater {
                    entries = result
                    reloadTable()
                }
            }
        })
    }

    private fun reloadTable() {
        val visible = visibleEntries()
        tableModel.rowCount = 0
        for (entry in visible) {
            tableModel.addRow(
                arrayOf<Any>(
                    entry.relativePath,
                    entry.status.label,
                    if (entry.sourceSize != null) formatSize(entry.sourceSize) else "—",
                    if (entry.targetSize != null) formatSize(entry.targetSize) else "—"
                )
            )
        }
        updateStatusLabel()
    }

    private fun visibleEntries(): List<FileDiffEntry> {
        val filter = filterCombo.selectedItem as? String ?: "全部"
        val keyword = searchField.text.trim().lowercase()
        val base = when (filter) {
            "仅源"   -> entries.filter { it.status == FileStatus.ONLY_SOURCE }
            "仅目标" -> entries.filter { it.status == FileStatus.ONLY_TARGET }
            "有差异" -> entries.filter { it.status == FileStatus.DIFFERENT }
            "一致"   -> entries.filter { it.status == FileStatus.SAME }
            else     -> entries
        }
        return if (keyword.isBlank()) base
        else base.filter { it.relativePath.lowercase().contains(keyword) }
    }

    private fun updateStatusLabel() {
        val onlySrc = entries.count { it.status == FileStatus.ONLY_SOURCE }
        val onlyTgt = entries.count { it.status == FileStatus.ONLY_TARGET }
        val diff = entries.count { it.status == FileStatus.DIFFERENT }
        val same = entries.count { it.status == FileStatus.SAME }
        val sel = fileTable.selectedRowCount
        val selPart = if (sel > 0) " | 已选: $sel" else ""
        statusLabel.text =
            "共 ${entries.size} 个文件 | 仅源: $onlySrc | 仅目标: $onlyTgt | 有差异: $diff | 一致: $same$selPart"
    }

    /** 获取当前选中的所有 entry（考虑排序映射） */
    private fun selectedEntries(): List<FileDiffEntry> {
        val visible = visibleEntries()
        return fileTable.selectedRows
            .map { fileTable.convertRowIndexToModel(it) }
            .mapNotNull { visible.getOrNull(it) }
    }

    private fun openDiffForSelectedRow() {
        val entry = selectedEntries().firstOrNull() ?: return
        DiffViewer.openDiff(project, entry)
    }

    private fun copySelectedRows() {
        val selected = selectedEntries()
        if (selected.isEmpty()) {
            notify("没有选中文件", NotificationType.WARNING)
            return
        }
        val copyable = selected.filter { it.sourceFile != null }
        val skipped = selected.size - copyable.size
        if (copyable.isEmpty()) {
            notify("选中均为「仅目标」文件，源不存在，无法拷贝", NotificationType.WARNING)
            return
        }
        val willOverwrite = copyable.count { it.status == FileStatus.DIFFERENT }
        if (willOverwrite > 0 || skipped > 0) {
            val msg = buildString {
                append("将拷贝 ${copyable.size} 个文件")
                if (willOverwrite > 0) append("，其中 $willOverwrite 个目标已有不同内容，会被源覆盖")
                if (skipped > 0) append("；已跳过 $skipped 个「仅目标」文件")
                append("。是否继续？")
            }
            val ok = Messages.showYesNoDialog(project, msg, "确认拷贝", Messages.getQuestionIcon())
            if (ok != Messages.YES) return
        }
        var success = 0
        var fail = 0
        for (entry in copyable) {
            val src = entry.sourceFile ?: continue
            val target = File(settings.targetPath, entry.relativePath)
            if (FileScanService.copyFile(src, target)) success++ else fail++
        }
        val type = if (fail == 0) NotificationType.INFORMATION else NotificationType.WARNING
        notify("拷贝完成：成功 $success，失败 $fail", type)
        refreshFileList()
    }

    private fun revealInExplorer() {
        val entry = selectedEntries().firstOrNull() ?: return
        val file = entry.sourceFile ?: entry.targetFile ?: return
        RevealFileAction.openFile(file)
    }

    private fun copyPath(absolute: Boolean) {
        val list = selectedEntries()
        if (list.isEmpty()) return
        val text = list.joinToString("\n") { entry ->
            if (absolute) {
                (entry.sourceFile ?: entry.targetFile)?.absolutePath ?: entry.relativePath
            } else entry.relativePath
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        notify("已复制 ${list.size} 条路径", NotificationType.INFORMATION)
    }

    /**
     * 监听文档保存：Diff 中编辑右侧目标文件并保存后，自动刷新对应条目状态，
     * 不需要用户手动点"刷新"。
     */
    private fun registerFileSaveListener() {
        project.messageBus.connect(toolWindow.disposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    val path = vf.path
                    val matched = entries.firstOrNull {
                        val expected = File(settings.targetPath, it.relativePath).absolutePath
                        it.targetFile?.absolutePath == path || expected == path
                    } ?: return
                    ApplicationManager.getApplication().invokeLater {
                        rescanSingleEntry(matched)
                    }
                }
            }
        )
    }

    private fun rescanSingleEntry(entry: FileDiffEntry) {
        val newEntry = FileScanService.rescan(entry, settings)
        entries = entries.map { if (it.relativePath == entry.relativePath) newEntry else it }
        reloadTable()
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Translation")
            .createNotification(message, type)
            .notify(project)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    // ─── 状态列颜色渲染 ───
    private class StatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) {
                when (value as? String) {
                    FileStatus.ONLY_SOURCE.label -> {
                        foreground = JBColor(0xE67E22, 0xE6A23C)
                        font = font.deriveFont(java.awt.Font.BOLD)
                    }
                    FileStatus.ONLY_TARGET.label -> {
                        foreground = JBColor(0x8E44AD, 0xB37FEB)
                        font = font.deriveFont(java.awt.Font.BOLD)
                    }
                    FileStatus.DIFFERENT.label -> foreground = JBColor(0xE74C3C, 0xF56C6C)
                    FileStatus.SAME.label      -> foreground = JBColor(0x27AE60, 0x67C23A)
                }
            }
            return c
        }
    }
}
