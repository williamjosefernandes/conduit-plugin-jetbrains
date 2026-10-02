package dev.conduit.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import java.awt.Font

/**
 * Colors code the way the editor does: the language's own lexer and the current color scheme,
 * so code in the chat looks like code in the editor, in any theme.
 */
object SyntaxHighlight {
    private const val MAX_CHARS = 40_000

    private val aliases =
        mapOf(
            "kotlin" to "kt",
            "java" to "java",
            "python" to "py",
            "py" to "py",
            "javascript" to "js",
            "js" to "js",
            "jsx" to "jsx",
            "typescript" to "ts",
            "ts" to "ts",
            "tsx" to "tsx",
            "json" to "json",
            "xml" to "xml",
            "html" to "html",
            "css" to "css",
            "scss" to "scss",
            "yaml" to "yaml",
            "yml" to "yaml",
            "sql" to "sql",
            "bash" to "sh",
            "sh" to "sh",
            "shell" to "sh",
            "zsh" to "sh",
            "groovy" to "groovy",
            "gradle" to "gradle",
            "go" to "go",
            "rust" to "rs",
            "rs" to "rs",
            "php" to "php",
            "ruby" to "rb",
            "markdown" to "md",
            "md" to "md",
            "properties" to "properties",
            "toml" to "toml",
            "dockerfile" to "Dockerfile",
            "c" to "c",
            "cpp" to "cpp",
            "csharp" to "cs",
            "cs" to "cs",
            "swift" to "swift",
            "dart" to "dart",
            "scala" to "scala",
        )

    /** The file type for a fence's language tag, or null when the IDE has no highlighter for it. */
    fun fileType(language: String): FileType? {
        val tag = language.trim().lowercase().substringBefore(' ')
        if (tag.isEmpty()) return null
        val manager = FileTypeManager.getInstance()
        val extension = aliases[tag] ?: tag
        val type = if (extension == "Dockerfile") manager.getFileTypeByFileName(extension) else manager.getFileTypeByExtension(extension)
        return type.takeUnless { it is UnknownFileType || it is PlainTextFileType }
    }

    /** [code] with the colors and font styles of the editor; plain when the language is unknown. */
    fun annotate(
        project: Project,
        code: String,
        language: String,
    ): AnnotatedString {
        val type = fileType(language)
        if (type == null || code.length > MAX_CHARS) return AnnotatedString(code)
        return runCatching {
            ApplicationManager.getApplication().runReadAction<AnnotatedString> {
                val scheme = EditorColorsManager.getInstance().globalScheme
                val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type)
                highlighter.setColorScheme(scheme)
                highlighter.setText(code)
                val iterator = highlighter.createIterator(0)
                buildAnnotatedString {
                    append(code)
                    while (!iterator.atEnd()) {
                        val attributes = iterator.textAttributes
                        val start = iterator.start.coerceIn(0, code.length)
                        val end = iterator.end.coerceIn(start, code.length)
                        val color = attributes.foregroundColor
                        val bold = attributes.fontType and Font.BOLD != 0
                        val italic = attributes.fontType and Font.ITALIC != 0
                        val styled = color != null || bold || italic
                        if (end > start && styled) {
                            addStyle(
                                SpanStyle(
                                    color = color?.let { Color(it.rgb) } ?: Color.Unspecified,
                                    fontWeight = if (bold) FontWeight.Bold else null,
                                    fontStyle = if (italic) FontStyle.Italic else null,
                                ),
                                start,
                                end,
                            )
                        }
                        iterator.advance()
                    }
                }
            }
        }.getOrElse { AnnotatedString(code) }
    }
}
