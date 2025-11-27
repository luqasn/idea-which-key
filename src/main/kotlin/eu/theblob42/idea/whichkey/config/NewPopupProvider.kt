package eu.theblob42.idea.whichkey.config

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.maddyhome.idea.vim.api.injector
import eu.theblob42.idea.whichkey.model.Mapping
import java.awt.*
import javax.swing.*
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.ui.popup.*
import com.intellij.util.ui.UIUtil
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import eu.theblob42.idea.whichkey.model.Mappings
import eu.theblob42.idea.whichkey.provider.PopupProvider
import java.awt.event.KeyEvent
import kotlin.math.ceil

val WHICHKEY_MAPPING_BINDING =
    TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_BINDING", DefaultLanguageHighlighterColors.NUMBER)
val WHICHKEY_MAPPING_ASSIGMENT =
    TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_ASSIGMENT", DefaultLanguageHighlighterColors.CONSTANT)
val WHICHKEY_MAPPING_ICON =
    TextAttributesKey.createTextAttributesKey("WHICHKEY_MAPPING_ICON", DefaultLanguageHighlighterColors.CONSTANT)
val WHICHKEY_MAPPING_DESCRIPTION = TextAttributesKey.createTextAttributesKey(
    "WHICHKEY_MAPPING_DESCRIPTION",
    DefaultLanguageHighlighterColors.LINE_COMMENT
)
val WHICHKEY_MAPPING_DESCRIPTION_GROUP = TextAttributesKey.createTextAttributesKey(
    "WHICHKEY_MAPPING_DESCRIPTION_GROUP",
    DefaultLanguageHighlighterColors.KEYWORD
)

fun getConfig(name: String) : VimDataType? = injector.variableService.getGlobalVariableValue("WhichKey_${name}")

fun getConfigInt(name: String) : Int? = when (val size = getConfig(name)) {
    is VimInt -> size.value
    else -> null
}

fun getConfigString(name: String) : String? = when (val size = getConfig(name)) {
    is VimString -> size.value
    else -> null
}

class NewPopupProvider: PopupProvider {
    companion object {
        const val name = "new"
    }
    private var currentPopup: JBPopup? = null
    override fun hidePopup() {
        currentPopup?.cancel()
        currentPopup = null
    }

    private val fontSize: Int? // font size in point
        get() = getConfigInt("FontSize")

    private val padding: Int
        get() = getConfigInt("Padding") ?: 1

    private val paddingHorizontal: Int?
        get() = getConfigInt("PaddingHorizontal")

    private val paddingVertical: Int?
        get() = getConfigInt("PaddingVertical")


    private val margin: Int
        get() = getConfigInt("Margin") ?: 1

    private val marginHorizontal: Int?
        get() = getConfigInt("MarginHorizontal")

    private val marginVertical: Int?
        get() = getConfigInt("MarginVertical")

    private val spacing: Int
        get() = getConfigInt("Spacing") ?: 3

    private val style: Style
        get() = when (val popupType = injector.variableService.getGlobalVariableValue("WhichKey_PopupStyle")) {
        !is VimString -> Style.BOTTOM
        else -> Style.entries.firstOrNull { it.name.compareTo(popupType.value, true) == 0 } ?: Style.BOTTOM
    }

    private fun show(editor: Editor, items: List<Item>) {
        val rowWidth = 40
        val containerWidth = if (style == Style.RIGHT) rowWidth else editor.calculateSizeInCharacters()?.width ?: 50
        val config = WhichKeyConfig(
            spacing = spacing,
            padding = DimensionConfig(paddingVertical ?: padding, paddingHorizontal ?: padding),
            margin = DimensionConfig(marginVertical ?: margin, marginHorizontal ?: margin),
        )
        val text = PopupLayout.layoutItems(
            40, containerWidth, config, items
        )
        val editorFactory: EditorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(text.text)
        val editor2: Editor = editorFactory.createViewer(document)
        editor2.document.setReadOnly(true)
        editor2.settings.apply {
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isAllowSingleLogicalLineFolding = true
            isAdditionalPageAtBottom = false
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isRightMarginShown = false
            isCaretRowShown = false
            isShowingSpecialChars = false
        }
        fontSize?.let {
            (editor2 as EditorImpl).fontSize = it
        }

        val markupModel = editor2.markupModel

        text.highlights.forEach {
            markupModel.addRangeHighlighter(it.startOffset, it.endOffset, 0, it.textAttributes, it.targetArea)
        }

        val lines = text.text.lines()

        val contentSize = editor.component.visibleRect.size
        val popupHeight = lines.size * editor.lineHeight

        val charSize = editor.getCharSize()

        val marginBottom = config.margin.vertical * editor.lineHeight
        val marginRight = config.margin.horizontal * ceil(charSize.width).toInt()

        val preferredSize = when(style){
            Style.BOTTOM -> Dimension(contentSize.width - 2*marginRight, popupHeight)
            Style.RIGHT -> Dimension(rowWidth * ceil(charSize.width).toInt(), popupHeight)
        }

        val component = object : JComponent() {
            init {
                layout = BorderLayout()

                val scrollPane = ScrollPaneFactory.createScrollPane(editor2.contentComponent, true).apply {
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                }
                scrollPane.preferredSize = preferredSize
                add(scrollPane, BorderLayout.CENTER)
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
        when(style) {
            Style.BOTTOM ->
                popup.show(RelativePoint(editor.component, Point(marginRight, contentSize.height - popupHeight - marginBottom)))

            Style.RIGHT -> {
                val scrollbarPadding = UIUtil.getScrollBarWidth()
                popup.show(RelativePoint(editor.component, Point(contentSize.width-preferredSize.width - scrollbarPadding - marginRight, contentSize.height - popupHeight - scrollbarPadding)))
            }
        }
        currentPopup = popup
    }


    private val keys = mapOf(
        KeyEvent.VK_UP to "",
        KeyEvent.VK_DOWN to "",
        KeyEvent.VK_LEFT to "",
        KeyEvent.VK_RIGHT to "",
        KeyEvent.VK_ENTER to "󰌑",
        KeyEvent.VK_ESCAPE to "󱊷",
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

    override fun showPopup(
        editor: Editor,
        typedKeys: List<KeyStroke>,
        nestedMappings: List<Pair<String, Mapping>>,
        startTime: Long
    ) {
        if (nestedMappings.isEmpty()) {
            return
        }
        val items = Mappings.sort(nestedMappings).map {
            val key = injector.parser.parseKeys(it.first).first()
            val keyCode = if (key.keyCode == 0) KeyEvent.getExtendedKeyCodeForChar(key.keyChar.code) else key.keyCode
            Item(
                Modifiers.fromKeyStroke(key),
                keys.getOrDefault(
                    keyCode,
                    if (key.keyCode == 0) key.keyChar.toString() else KeyEvent.getKeyText(key.keyCode)
                ),
                null,
                it.second.description,
                it.second.prefix
            )
        }

        show(editor, items)
    }
    enum class Style {
        BOTTOM,
        RIGHT
    }
}
