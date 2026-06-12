package cn.minmetals.hcm.pm.plugin.codetrans

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * 文件差异状态
 */
enum class FileStatus(val label: String) {
    ONLY_SOURCE("仅源"),
    ONLY_TARGET("仅目标"),
    DIFFERENT("有差异"),
    SAME("一致")
}

/**
 * 文件对比结果（不可变）
 *
 * 当 status == ONLY_TARGET 时 sourceFile 为 null；
 * 当 status == ONLY_SOURCE 时 targetFile 为 null；
 * 其它状态两侧都应存在。
 */
data class FileDiffEntry(
    val relativePath: String,
    val sourceFile: File?,
    val targetFile: File?,
    val status: FileStatus,
    val sourceSize: Long?,
    val targetSize: Long?
)

/**
 * 扫描源目录，按后缀过滤，对比目标目录。
 */
object FileScanService {
    private val LOG = logger<FileScanService>()

    fun scan(
        settings: TranslationSettings,
        indicator: ProgressIndicator? = null,
        nameFilter: String = ""
    ): List<FileDiffEntry> {
        val sourceDir = Path(settings.sourcePath)
        val targetDir = Path(settings.targetPath)
        if (!sourceDir.exists() || !targetDir.exists()) return emptyList()

        val extensions = settings.fileExtensions
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (extensions.isEmpty()) return emptyList()

        val ignoredDirs = settings.ignoredDirectories
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val ignoredFileRegexes = settings.ignoredFilePatterns
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { globToRegex(it) }

        val keyword = nameFilter.trim().lowercase()

        fun acceptableFiles(root: java.nio.file.Path) = root.toFile().walkTopDown()
            .onEnter { dir -> dir.name !in ignoredDirs && !dir.name.startsWith(".") }
            .filter { it.isFile }
            .filter { !it.name.startsWith(".") }
            .filter { ".${it.extension}".lowercase() in extensions }
            .filter { f -> ignoredFileRegexes.none { r -> r.matches(f.name) } }
            .filter { f ->
                if (keyword.isBlank()) true
                else root.relativize(f.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                    .lowercase()
                    .contains(keyword)
            }

        // 早剪枝：onEnter 阻止进入忽略目录；take 真正中断遍历
        val sourceFiles = acceptableFiles(sourceDir)
            .take(settings.maxFiles)
            .toList()

        val seenRelativePaths = HashSet<String>(sourceFiles.size)
        val total = sourceFiles.size.coerceAtLeast(1)
        val results = ArrayList<FileDiffEntry>(sourceFiles.size)

        sourceFiles.forEachIndexed { idx, srcFile ->
            indicator?.checkCanceled()
            indicator?.fraction = idx.toDouble() / total
            indicator?.text2 = srcFile.name

            val relative = sourceDir.relativize(srcFile.toPath())
            val relativeStr = relative.toString().replace(File.separatorChar, '/')
            seenRelativePaths.add(relativeStr)
            val targetFile = targetDir.resolve(relative).toFile()

            val status = when {
                !targetFile.exists() -> FileStatus.ONLY_SOURCE
                filesEqual(srcFile, targetFile) -> FileStatus.SAME
                else -> FileStatus.DIFFERENT
            }

            results.add(
                FileDiffEntry(
                    relativePath = relativeStr,
                    sourceFile = srcFile,
                    targetFile = targetFile.takeIf { it.exists() },
                    status = status,
                    sourceSize = srcFile.length(),
                    targetSize = if (targetFile.exists()) targetFile.length() else null
                )
            )
        }

        // 第二遍：补充仅目标的文件（源里没有对应路径）
        val remainingQuota = (settings.maxFiles - results.size).coerceAtLeast(0)
        if (remainingQuota > 0) {
            indicator?.text2 = "扫描目标目录…"
            acceptableFiles(targetDir)
                .asSequence()
                .mapNotNull { tgtFile ->
                    val relativeStr = targetDir.relativize(tgtFile.toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                    if (relativeStr in seenRelativePaths) null
                    else relativeStr to tgtFile
                }
                .take(remainingQuota)
                .forEach { (relativeStr, tgtFile) ->
                    indicator?.checkCanceled()
                    indicator?.text2 = tgtFile.name
                    results.add(
                        FileDiffEntry(
                            relativePath = relativeStr,
                            sourceFile = null,
                            targetFile = tgtFile,
                            status = FileStatus.ONLY_TARGET,
                            sourceSize = null,
                            targetSize = tgtFile.length()
                        )
                    )
                }
        }

        return results.sortedBy { it.relativePath }
    }

    /** Diff 编辑后单条目重新计算状态，避免全量重扫。 */
    fun rescan(entry: FileDiffEntry, settings: TranslationSettings): FileDiffEntry {
        val targetFile = File(settings.targetPath, entry.relativePath)
        val src = entry.sourceFile
        val status = when {
            src == null || !src.exists() ->
                if (targetFile.exists()) FileStatus.ONLY_TARGET else entry.status
            !targetFile.exists() -> FileStatus.ONLY_SOURCE
            filesEqual(src, targetFile) -> FileStatus.SAME
            else -> FileStatus.DIFFERENT
        }
        return entry.copy(
            targetFile = targetFile.takeIf { it.exists() },
            status = status,
            targetSize = if (targetFile.exists()) targetFile.length() else null
        )
    }

    /** 拷贝文件到目标路径（覆盖） */
    fun copyFile(src: File, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            true
        } catch (e: Exception) {
            LOG.warn("copy failed: ${src.absolutePath} -> ${dest.absolutePath}", e)
            false
        }
    }

    /** JDK 12+ 流式比较，不会读整文件到内存 */
    private fun filesEqual(f1: File, f2: File): Boolean {
        if (f1.length() != f2.length()) return false
        return Files.mismatch(f1.toPath(), f2.toPath()) == -1L
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (c in glob) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append(".")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '\\' -> sb.append("\\").append(c)
            else -> sb.append(c)
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}
