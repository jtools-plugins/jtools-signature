package com.lhstack.signature

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.HighlighterFactory
import com.intellij.lang.properties.PropertiesFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.getUserData
import com.intellij.openapi.ui.putUserData
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.LanguageTextField
import com.intellij.util.lang.JavaVersion
import com.lhstack.tools.plugins.Helper
import com.lhstack.tools.plugins.IPlugin
import org.apache.commons.codec.digest.DigestUtils
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.CRC32C
import javax.swing.Icon
import javax.swing.JComponent
import kotlin.io.path.readBytes

val dataType = Key.create<Int>("JTools-Signature:DataType")

val dataValue = Key.create<Path>("JTools-Signature:DataValue")

class PluginImpl : IPlugin {

    companion object {
        val INPUT_CACHE = mutableMapOf<String, LanguageTextField>()

        val OUTPUT_CACHE = mutableMapOf<String, LanguageTextField>()

        val DISPOSER = mutableMapOf<String,Disposable>()

        val COMPONENT_CACHE = mutableMapOf<String, JComponent>()
    }

    override fun pluginIcon(): Icon? = Helper.findIcon("plugin.svg", PluginImpl::class.java)

    override fun pluginTabIcon(): Icon? = Helper.findIcon("plugin-tab.svg", PluginImpl::class.java)

    override fun pluginName(): String = "内容+文件签名"

    override fun pluginDesc(): String = "计算内容或者文件的md5,sha1,sha256等签名值"

    override fun pluginVersion(): String = "0.0.2"

    override fun createPanel(project: Project): JComponent = COMPONENT_CACHE.computeIfAbsent(project.locationHash) {
        JBSplitter(true).apply {
            this.dividerWidth = 2
            this.proportion = 0.5f
            val parentDisposable = Disposer.newDisposable()
            this.firstComponent =
                INPUT_CACHE.computeIfAbsent(project.locationHash) { createTextField(project, parentDisposable = parentDisposable) }.apply {
                    val that = this
                    this.addDocumentListener(object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            //文本
                            that.putUserData(dataType, 0)
                            checksum(project)
                        }
                    })
                }
            this.secondComponent =
                OUTPUT_CACHE.computeIfAbsent(project.locationHash) { createTextField(project,
                    PropertiesFileType.INSTANCE, parentDisposable = parentDisposable) }
            DISPOSER[project.locationHash] = parentDisposable
        }
    }

    override fun closeProject(project: Project) {
        DISPOSER.remove(project.locationHash)?.let {
            Disposer.dispose(it)
        }
        COMPONENT_CACHE.remove(project.locationHash)
        INPUT_CACHE.remove(project.locationHash)
        OUTPUT_CACHE.remove(project.locationHash)
    }

    override fun tabPanelActions(project: Project): MutableList<AnAction> =
        mutableListOf(
            object : ToggleAction({ "输入的数据类型是否为文件" }, AllIcons.FileTypes.AS) {

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }

                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.description = "输入的数据类型是否为文件"
                }

                override fun isSelected(e: AnActionEvent): Boolean = (INPUT_CACHE[project.locationHash]?.getUserData(
                    dataType
                ) ?: 0) == 1

                override fun setSelected(e: AnActionEvent, state: Boolean) {

                }
            },
            object : AnAction({ "选择文件" }, AllIcons.Actions.FindEntireFile) {
                override fun actionPerformed(e: AnActionEvent) {
                    INPUT_CACHE[project.locationHash]?.let { input ->
                        val fileChooserDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
                        FileChooserFactory.getInstance().createFileChooser(fileChooserDescriptor, project, null)
                            .choose(project)
                            .let {
                                if (it.size == 1) {
                                    input.text = it[0].name
                                    input.putUserData(dataType, 1)
                                    input.putUserData(dataValue, it[0].toNioPath())
                                    checksum(project)
                                }
                            }
                    }
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            }, object : AnAction({ "checksum" }, AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) {
                    checksum(project)
                }

                override fun getActionUpdateThread(): ActionUpdateThread {
                    return ActionUpdateThread.EDT
                }
            })

    fun checksum(project: Project){
        OUTPUT_CACHE[project.locationHash]?.let { output ->
            INPUT_CACHE[project.locationHash]?.let { input ->
                val type = input.getUserData(dataType) ?: 0
                val bytes = when (type) {
                    0 -> input.text.toByteArray(Charsets.UTF_8)
                    else -> {
                        input.getUserData(dataValue)?.readBytes()
                    }
                }
                if (bytes?.isEmpty() == true) {
                    output.text = "请输入或者选择需要签名的对象"
                    return
                }
                output.text = """
                                    CRC32=${
                    CRC32().let {
                        it.update(bytes)
                        it.value
                    }
                }
                                    CRC32C=${
                    CRC32C().let {
                        it.update(bytes)
                        it.value
                    }
                }
                                    md2=${DigestUtils.md2Hex(bytes)}
                                    md5=${DigestUtils.md5Hex(bytes)}
                                    sha1=${DigestUtils.sha1Hex(bytes)}
                                    sha256=${DigestUtils.sha256Hex(bytes)}
                                    sha384=${DigestUtils.sha384Hex(bytes)}
                                    sha512=${DigestUtils.sha512Hex(bytes)}
                                    sha3_224=${DigestUtils.sha3_224Hex(bytes)}
                                    sha3_256=${DigestUtils.sha3_256Hex(bytes)}
                                    sha3_384=${DigestUtils.sha3_384Hex(bytes)}
                                    sha3_512=${DigestUtils.sha3_512Hex(bytes)}
                                    sha512_224=${DigestUtils.sha512_224Hex(bytes)}
                                    sha512_256=${DigestUtils.sha512_256Hex(bytes)}
                                """.trimIndent()
            }

        }
    }

    override fun support(jToolsVersion: Int): Boolean {
        return JavaVersion.current().feature >= 17
    }

    private fun createTextField(project: Project,fileType:LanguageFileType = PlainTextFileType.INSTANCE, parentDisposable: Disposable): LanguageTextField =
        object : LanguageTextField(fileType.language, project, "", false) {
            override fun createEditor(): EditorEx {
                val editorEx = EditorFactory.getInstance()
                    .createEditor(document, project, fileType, false) as EditorEx
                editorEx.highlighter = HighlighterFactory.createHighlighter(project, fileType)
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(
                    editorEx.document
                )
                if (psiFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).setHighlightingEnabled(psiFile, true)
//            if(psiFile is PsiJavaFile){
//                DaemonCodeAnalyzer.getInstance(project).setImportHintsEnabled(psiFile,true)
//            }else if(psiFile is GroovyFile){
//                DaemonCodeAnalyzer.getInstance(project).setImportHintsEnabled(psiFile,true)
//            }
                }
                editorEx.setBorder(null)
                val settings = editorEx.settings
                //去掉折叠轮廓列,编辑器中
                settings.isFoldingOutlineShown = false
                settings.additionalLinesCount = 0
                settings.additionalColumnsCount = 1
                settings.isLineNumbersShown = true
                settings.isFoldingOutlineShown = false
                settings.isUseSoftWraps = true
                settings.lineCursorWidth = 1
                settings.isLineMarkerAreaShown = false
                settings.setRightMargin(-1)
                Disposer.register(parentDisposable) {
                    if(editorEx is EditorImpl){
                        if(!editorEx.isDisposed){
                            EditorFactory.getInstance().releaseEditor(editorEx)
                        }
                    }else {
                        EditorFactory.getInstance().releaseEditor(editorEx)
                    }

                }
                return editorEx
            }
        }
}