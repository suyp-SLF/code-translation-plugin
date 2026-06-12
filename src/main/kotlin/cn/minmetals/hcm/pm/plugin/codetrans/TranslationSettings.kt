package cn.minmetals.hcm.pm.plugin.codetrans

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 项目级持久化配置，存储到 .idea/codeTranslationSettings.xml。
 */
@State(name = "CodeTranslationSettings", storages = [Storage("codeTranslationSettings.xml")])
class TranslationSettings : PersistentStateComponent<TranslationSettings> {

    /** 源目录绝对路径（只读） */
    var sourcePath: String = ""

    /** 目标目录绝对路径（可写，即当前项目代码目录） */
    var targetPath: String = ""

    /** 要扫描的文件后缀，逗号分隔（如 .java,.xml,.properties,.yaml） */
    var fileExtensions: String = ".java,.xml,.properties,.yaml,.yml"

    /** 每次扫描最大文件数 */
    var maxFiles: Int = 500

    /** 忽略的目录名（逗号分隔） */
    var ignoredDirectories: String = ".git,.idea,target,build,out,classes,node_modules,.svn"

    /** 忽略的文件名 glob 模式（逗号分隔，可为空），如 "*Test.java,*.class" */
    var ignoredFilePatterns: String = ""

    override fun getState(): TranslationSettings = this

    override fun loadState(state: TranslationSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): TranslationSettings =
            project.getService(TranslationSettings::class.java)
    }
}
