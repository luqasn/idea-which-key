package eu.theblob42.idea.whichkey.config

import com.intellij.ide.ui.AntialiasingType
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsUtils
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.impl.FontInfo
import com.intellij.openapi.editor.impl.view.FontLayoutService
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ImageLoader
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import eu.theblob42.idea.whichkey.model.Mapping
import kotlinx.coroutines.*
import java.awt.*
import java.awt.font.FontRenderContext
import javax.swing.*
import javax.swing.text.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.ui.popup.*
import com.maddyhome.idea.vim.command.CommandState
import java.awt.event.KeyEvent
import java.awt.event.KeyListener

val WHICHKEY_MAPPING_BINDING = TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_BINDING", DefaultLanguageHighlighterColors.NUMBER)
val WHICHKEY_MAPPING_ASSIGMENT = TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_ASSIGMENT", DefaultLanguageHighlighterColors.CONSTANT)
val WHICHKEY_MAPPING_ICON = TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_ICON", DefaultLanguageHighlighterColors.CONSTANT)
val WHICHKEY_MAPPING_DESCRIPTION = TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_DESCRIPTION", DefaultLanguageHighlighterColors.LINE_COMMENT)
val WHICHKEY_MAPPING_DESCRIPTION_GROUP = TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_DESCRIPTION_GROUP", DefaultLanguageHighlighterColors.KEYWORD)

object PopupConfigCode {

    private const val DEFAULT_POPUP_DELAY = 200
    private val defaultPopupDelay: Int
        get() = when (val delay = injector.variableService.getGlobalVariableValue("WhichKey_DefaultDelay")) {
            null -> DEFAULT_POPUP_DELAY
            !is VimInt -> DEFAULT_POPUP_DELAY
            else -> delay.value
        }

    private val DEFAULT_SORT_OPTION = SortOption.BY_KEY
    private val sortOption: SortOption
        get() = when (val option = injector.variableService.getGlobalVariableValue("WhichKey_SortOrder")) {
            null -> DEFAULT_SORT_OPTION
            !is VimString -> DEFAULT_SORT_OPTION
            else -> SortOption.values().firstOrNull { it.name.equals(option.asString(), ignoreCase = true) }
                ?: DEFAULT_SORT_OPTION
        }

    private const val DEFAULT_SORT_CASE_SENSITIVE = true
    private val sortCaseSensitive: Boolean
        get() = when (val option = injector.variableService.getGlobalVariableValue("WhichKey_SortCaseSensitive")) {
            null -> DEFAULT_SORT_CASE_SENSITIVE
            !is VimString -> DEFAULT_SORT_CASE_SENSITIVE
            else -> option.asString().toBoolean()
        }

    private var currentPopup: JBPopup? = null

    /**
     * Either cancel the display job or hide the current popup
     */
    fun hidePopup() {
        currentPopup?.cancel()
    }

    internal fun selectEditorFont(editor: Editor?, forText: String): Font {
        val fontSize = when {
            editor is EditorImpl -> editor.fontSize2D
            UISettings.getInstance().presentationMode -> UISettingsUtils.getInstance().presentationModeFontSize
            editor?.editorKind == EditorKind.CONSOLE -> UISettingsUtils.getInstance().scaledConsoleFontSize
            else -> UISettingsUtils.getInstance().scaledEditorFontSize
        }

        val scheme = EditorColorsManager.getInstance().globalScheme
        scheme.fontPreferences.realFontFamilies.forEach { fontName ->
            val font = Font(fontName, Font.PLAIN, scheme.editorFontSize)
            if (font.canDisplayUpTo(forText) == -1) {
                return font.deriveFont(fontSize)
            }
        }

        return Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize).deriveFont(fontSize)
    }

//    private fun changeLineSpacing(pane: JEditorPane) {
//        val set = SimpleAttributeSet(pane.)
//        StyleConstants.setLineSpacing(set, 0.5f)
//        pane.setParagraphAttributes(set, true)
//    }
private fun dim(size: Int, parent: Int, vararg dims: SizeConfig): Int {
    var adjustedSize = if (kotlin.math.abs(size) < 1) parent * size else size
    adjustedSize = if (adjustedSize < 0) parent + adjustedSize else adjustedSize

    for (dim in dims) {
        val min = dim(dim.min, parent) ?: 0
        val max = dim(dim.max, parent) ?: parent
        adjustedSize = max(min, min(max, adjustedSize))
    }
    return floor(max(0, min(parent, adjustedSize)) + 0.5).toInt()
}
    data class Modifier(val icon: String)
    data class Item(val modifiers: List<Modifier>, val code: String, val icon: String?, val description: String, val isGroup: Boolean)

    data class Highlight(
        val startOffset: Int,
        val endOffset: Int,
//            val layer: Int,
        val textAttributes: TextAttributes?,
        val targetArea: HighlighterTargetArea
    )
    data class TextWithHighlights(val text: String, val highlights: List<Highlight>)

    val colors = listOf(JBColor.RED, JBColor.BLUE, JBColor.GREEN, JBColor.ORANGE)

    private fun makeExactly(str: String, widthInCodePoints: Int): String {
        val builder = StringBuilder(str)
        var len = builder.length
        while (builder.codePointCount(0, len) > widthInCodePoints) {
            len -= 1
        }
        while (builder.codePointCount(0, len) < widthInCodePoints) {
            builder.append(' ')
            len += 1
        }
        return builder.toString().substring(0, len-1)
    }
    fun layoutItems(
        maxRowWidth: Int,
        containerWidth: Int,
        metrics: FontMetrics,
        charSize: ImageLoader.Dimension2DDouble,
        config: WhichKeyConfig,
        items: List<Item>,
    ): TextWithHighlights {
        val text = StringBuilder()
        val highlights = mutableListOf<Highlight>()
        var boxWidth = dim(maxRowWidth, containerWidth, config.width)
        val boxCount = max((containerWidth / (boxWidth + config.spacing)), 1)
        boxWidth = containerWidth / boxCount
        val boxHeight = max(ceil(items.size.toDouble() / boxCount).toInt(), 2)

        val scheme = EditorColorsManager.getInstance().globalScheme

//    val rows = t.layout(LayoutParams(width = boxWidth - config.layout.spacing))

        repeat(config.padding.first) {
            text.append("\n")
        }
        val targetLength = charSize.width * boxWidth
        fun appendWithHighlight(str: String, highlight: TextAttributesKey) {
            highlights.add(Highlight(text.length, text.length + str.length, scheme.getAttributes(highlight), HighlighterTargetArea.EXACT_RANGE))
           text.append(str)
        }
        val columns = items.withIndex().groupBy { it.index / boxHeight }

        for (lineIndex in 0..boxHeight-1) {
            text.append(" ".repeat(config.padding.second))
            for (b in 0..boxCount-1) {
                val i = b * boxHeight + lineIndex
                val item = items.getOrNull(i)
//            val row = rows.getOrNull(i)
                if (b > 0) {
                    text.append(" ".repeat(config.spacing))
                }
                val column = columns[b]
                val modifierSpaceRequired = column?.maxOf { it.value.modifiers.size }
                val needsIconSpace = column?.any { it.value.icon != null }
                if (item != null) {
                    if (modifierSpaceRequired != null) {
                        text.append(" ".repeat(modifierSpaceRequired - item.modifiers.size))
                    }
                    for (mod in item.modifiers) {
                        appendWithHighlight(mod.icon, WHICHKEY_MAPPING_BINDING)
                    }
                    if (modifierSpaceRequired != null) {
                        text.append(" ")
                    }
                    appendWithHighlight(item.code, WHICHKEY_MAPPING_BINDING)
                    appendWithHighlight(" ➜ ", WHICHKEY_MAPPING_ASSIGMENT)
                    if (needsIconSpace == true) {
                        appendWithHighlight(item.icon ?: " " , WHICHKEY_MAPPING_ICON)
                        text.append(" ")
                    }

                    appendWithHighlight((if (item.isGroup) "+" else "") + item.description, if (item.isGroup) WHICHKEY_MAPPING_DESCRIPTION_GROUP else WHICHKEY_MAPPING_DESCRIPTION)
                    val nonDescriptionLength = 5 + listOfNotNull(
                        2.takeIf { needsIconSpace == true },
                        1.takeIf { modifierSpaceRequired != null },
                        1.takeIf { item.isGroup }
                    ).sum()

                    text.append(" ".repeat((boxWidth-item.description.length-nonDescriptionLength).coerceAtLeast(0)))
                } else {
                    text.append(" ".repeat(boxWidth))
                }
           /*     fun makeExactly(str: String, widthInChars: Int): String {
                    if (metrics.stringWidth(str) < widthInChars * charSize.width) {

                    } else if (metrics.stringWidth(str) > widthInChars * charSize.width) {

                    }
                }
                val currentLength = metrics.stringWidth(itemText)*/


//                text.append(itemText.take(boxWidth).let{ if (b < boxCount-1) { it.padEnd(boxWidth) } else { it }})
//                text.append(" ".repeat((boxWidth - item.length).coerceAtLeast(0)))
            }
            text.append(" ".repeat(config.padding.second))
            text.append("\n")
        }

        repeat(config.padding.first) {
            text.append("\n")
        }
        return TextWithHighlights(text.toString(), highlights)
    }

    private fun show(editor: Editor, text: TextWithHighlights) {
        val editorFactory: EditorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(text.text)
        val editor2: Editor = editorFactory.createViewer(document)
        editor2.document.setReadOnly(true)
        editor2.settings.apply {
            isLineMarkerAreaShown = false;
            isIndentGuidesShown = false;
            isLineNumbersShown = false;
            isFoldingOutlineShown = false;
            isAllowSingleLogicalLineFolding = true
            isAdditionalPageAtBottom = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isRightMarginShown = false
            isCaretRowShown = false
            isShowingSpecialChars = false
        }

        val markupModel = editor2.markupModel

        text.highlights.forEach {
            markupModel.addRangeHighlighter(it.startOffset, it.endOffset, 0, it.textAttributes, it.targetArea)
        }

        val editorPane = editor2.contentComponent
//        val editorPane = JEditorPane("text/plain", text).apply {
//            isEditable = false
////            font = selectEditorFont(editor, text)
////            font = EditorUtil.getEditorFont()
//            background = editor.contentComponent.background
//        }
//        editorPane.editorKit.install(editorPane)
//        changeLineSpacing(editorPane)

//        myLabel.setFont(UiHelper.selectEditorFont(myEditor, myLabel.getText()));
        val metrics: FontMetrics = editorPane.getFontMetrics(editorPane.font)

        val lines = text.text.lines()
//            text.split(Pattern.quote("\n").toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        val config = WhichKeyConfig()


//        editor2.component.size = Dimension(max, lines.size  * editor.lineHeight)
//    editor2.component.setBounds(0,0, max, lines.size * editor.lineHeight)
//    (editor2 as EditorEx).highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(GooseSwanHighlighter(), EditorColorsManager.getInstance().globalScheme)


//        val textPanel = JPanel(BorderLayout())
//        textPanel.add(editorPane, BorderLayout.CENTER)
        val contentComponent = editor.contentComponent
        val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, contentComponent)


        val contentSize = editor.component.visibleRect.size
        val popupHeight = lines.size * editor.lineHeight
        val component = object : JComponent() {
            init {
                layout = BorderLayout()

                val scrollPane = ScrollPaneFactory.createScrollPane(editor2.contentComponent, true).apply {
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }
//        val scrollPane = JBScrollPane(editor2.contentComponent).apply {
//            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
//            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
//        }

                scrollPane.preferredSize = Dimension(contentSize.width, popupHeight)
                add(scrollPane, BorderLayout.CENTER)
//        editor2.contentComponent.preferredSize = Dimension(max, lines.size * editor.lineHeight)
//            add(editor2.contentComponent, BorderLayout.CENTER)
            }
        }


        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(component, null)
            .setFocusable(false)
//            .setRequestFocus(false)
            .setShowBorder(true)
//            .setMovable(true)
            .setMayBeParent(false)
            .setCancelOnClickOutside(false)
//            .setCancelOnWindowDeactivation(false)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
//            .addListener(object: JBPopupListener {
//                override fun onClosed(event: LightweightWindowEvent) {
//                    val commandState = CommandState.getInstance(editor)
//                    commandState.mappingState.resetMappingSequence()
//                    super.onClosed(event)
//                }
//            })
            .createPopup()
        popup.show(RelativePoint(editor.component, Point(0, contentSize.height - popupHeight)))
        currentPopup = popup
    }
    val mods = mapOf(
        KeyEvent.CTRL_DOWN_MASK to "󰘴",
        KeyEvent.ALT_DOWN_MASK to "󰘵",
//        D to "󰘳 ",
        KeyEvent.SHIFT_DOWN_MASK to "󰘶",
    )
    val keys = mapOf<Int, String>(
        KeyEvent.VK_UP to "",
        KeyEvent.VK_DOWN to "",
        KeyEvent.VK_LEFT to "",
        KeyEvent.VK_RIGHT to "",
        KeyEvent.VK_ENTER to "󰌑",
        KeyEvent.VK_ESCAPE to "󱊷",
//        KeyEvent to "󱕐 ",
//        ScrollWheelUp to "󱕑 ",
//        NL to "󰌑 ",
        KeyEvent.VK_BACK_SPACE to "󰁮",
        KeyEvent.VK_SPACE to "󱁐",
        KeyEvent.VK_TAB to "󰌒",
        KeyEvent.VK_F1 to "󱊫",
        KeyEvent.VK_F2 to "󱊬",
        KeyEvent.VK_F3 to "󱊭",
        KeyEvent.VK_F4 to "󱊮",
        KeyEvent.VK_F5 to "󱊯",
        KeyEvent.VK_F6 to "󱊰",
        KeyEvent.VK_F7 to "󱊱",
        KeyEvent.VK_F8 to "󱊲",
        KeyEvent.VK_F9 to "󱊳",
        KeyEvent.VK_F10 to "󱊴",
        KeyEvent.VK_F11 to "󱊵",
        KeyEvent.VK_F12 to "󱊶",
    )

private fun Editor.getCharSize(): ImageLoader.Dimension2DDouble {
    val baseContext = FontInfo.getFontRenderContext(contentComponent)
    val context = FontRenderContext(baseContext.transform,
        AntialiasingType.getKeyForCurrentScope(true),
        UISettings.editorFractionalMetricsHint)
    val fontMetrics = FontInfo.getFontMetrics(colorsScheme.getFont(EditorFontType.PLAIN), context)
    // Using the '%' to calculate the size as it's usually one of the widest non-double-width characters.
    // For monospaced fonts this shouldn't really matter, but let's stay on the safe side.
    // Otherwise, we may end up with some characters falsely displayed as double-width ones.
    val width = FontLayoutService.getInstance().charWidth2D(fontMetrics, '%'.code)
    return ImageLoader.Dimension2DDouble(width.toDouble(), lineHeight.toDouble())
}

    data class EditorSizeInCharacters(val width: Int, val height: Int)
    private fun Editor.calculateSizeInCharacters(): EditorSizeInCharacters? {
//        val contentSize = scrollingModel.visibleArea.size
        val contentSize = component.visibleRect.size
        val charSize = getCharSize()

        return if (contentSize.width > 0 && contentSize.height > 0) {
            return EditorSizeInCharacters((contentSize.width/charSize.width).toInt(), (contentSize.height/charSize.height).toInt())
        }
        else null
    }
//    val testItems = listOf(
//        Item("e", "󰙅", "Explorer NeoTree (Root Dir)"),
//        Item("E", "󰙅", "Explorer NeoTree (cwd)"),
//        Item("K", null, "Keywordprg"),
//        Item("l", "󰒲", "Lazy"),
//        Item("L", "󰒲", "LazyVim Changelog"),
//        Item("n", "󱥰", "Notification History"),
//        Item("S", "󱥰", "Select Scratch Buffer"),
//        Item(",", "󱡠", "Switch Buffer"),
//        Item("-", "", "Split Window Below"),
//        Item(".", "󱥰", "Toggle Scratch Buffer"),
//        Item("/", "󱡠", "Grep (Root Dir)"),
//        Item(":", "󱡠", "Command History"),
//        Item("?", "󰈔", "Buffer Keymaps (which-key)"),
//        Item("`", "󰈔", "Switch to Other Buffer"),
//        Item("|", "", "Split Window Right"),
//        Item("󱁐", "󱡠", "Find Files (Root Dir)"),
//        Item("b", "󰈔", "+buffer"),
//        Item("c", "\ueac4", "+code"),
//        Item("d", "󰃤", "+debug"),
//        Item("f", "\uDB80\uDF49", "+file/find"),
//        Item("g", "󰊢", "+git"),
//        Item("q", "", "+quit/session"),
//        Item("s", "󰍉", "+search"),
//        Item("u", "󰙵", "+ui"),
//        Item("w", "", "+windows"),
//        Item("x", "󱖫", "+diagnostics/quickfix"),
//        Item("󰌒", "󰓩", "+tabs"),
//    )

    /**
     * Show the popup presenting the nested mappings for [typedKeys]
     * Do not show the popup instantly but instead start a coroutine job to show the popup after a delay
     *
     * If there are no 'nestedMappings' (empty list) this function does nothing
     *
     * @param ideFrame The [JFrame] to attach the popup to
     * @param typedKeys The already typed key stroke sequence
     * @param nestedMappings A [List] of nested mappings to display
     * @param startTime Timestamp to consider for the calculation of the popup delay
     */
    fun showPopup(
        editor: Editor,
        typedKeys: List<KeyStroke>,
        nestedMappings: List<Pair<String, Mapping>>,
        startTime: Long
    ) {
        if (nestedMappings.isEmpty()) {
            return
        }
        fun getMods(modifiers: Int) = mods.entries.filter{ it.key and modifiers == it.key}.map { it.value }
        val metrics: FontMetrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val items = sortMappings(nestedMappings).map {
            val key = injector.parser.parseKeys(it.first).first()
            val keyCode = if (key.keyCode == 0) KeyEvent.getExtendedKeyCodeForChar(key.keyChar.code) else key.keyCode
            Item(
                getMods(key.modifiers).map(::Modifier),
                keys.getOrDefault(keyCode, if (key.keyCode == 0) key.keyChar.toString() else KeyEvent.getKeyText(key.keyCode)),
                it.second.icon,
                it.second.description,
                it.second.prefix
            )
        }
        val text = layoutItems(
            40, editor.calculateSizeInCharacters()?.width ?: 50, metrics, editor.getCharSize(), WhichKeyConfig(), items)
        show(editor, text)
        return
    }

    /**
     * Sort mappings dependent on the configured sort options
     * @param nestedMappings The list of mappings to sort
     * @return The sorted list of mappings
     */
    private fun sortMappings(nestedMappings: List<Pair<String, Mapping>>): List<Pair<String, Mapping>> {
        // String::compareTo is by default case-sensitive
        val cmp = if (sortCaseSensitive) String::compareTo else String.CASE_INSENSITIVE_ORDER::compare

        return when (sortOption) {
            SortOption.BY_KEY -> nestedMappings.sortedWith(compareBy(cmp) { it.first })
            SortOption.BY_KEY_PREFIX_FIRST -> nestedMappings.sortedWith(
                compareBy<Pair<String, Mapping>> { !it.second.prefix }.thenBy(
                    cmp
                ) { it.first })

            SortOption.BY_KEY_PREFIX_LAST -> nestedMappings.sortedWith(
                compareBy<Pair<String, Mapping>> { it.second.prefix }.thenBy(
                    cmp
                ) { it.first })

            SortOption.BY_DESCRIPTION -> nestedMappings.sortedWith(compareBy(cmp) { it.second.description })
        }
    }
}
