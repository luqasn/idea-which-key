package eu.theblob42.idea.whichkey.provider

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import eu.theblob42.idea.whichkey.config.DefaultPopupProvider
import eu.theblob42.idea.whichkey.config.NewPopupProvider
import eu.theblob42.idea.whichkey.model.Mapping
import kotlinx.coroutines.*
import javax.swing.KeyStroke

interface PopupProvider {
    fun showPopup(
        editor: Editor,
        typedKeys: List<KeyStroke>,
        nestedMappings: List<Pair<String, Mapping>>,
        startTime: Long,
    )
    fun hidePopup()
}

class ConfigurablePopupProvider : PopupProvider {
    private val defaultPopupProvider = DefaultPopupProvider()
    private val alternativePopupProviders = mapOf(
        NewPopupProvider.name to NewPopupProvider(),
    )

    private val debouncedPopupProvider = DebouncingPopupProvider {
        when (val popupType = injector.variableService.getGlobalVariableValue("WhichKey_PopupType")) {
            !is VimString -> defaultPopupProvider
            else -> alternativePopupProviders.getOrDefault(popupType.value, defaultPopupProvider)
        }
    }

    override fun showPopup(
        editor: Editor,
        typedKeys: List<KeyStroke>,
        nestedMappings: List<Pair<String, Mapping>>,
        startTime: Long
    ) {
        debouncedPopupProvider.showPopup(editor, typedKeys, nestedMappings, startTime)
    }

    override fun hidePopup() {
        debouncedPopupProvider.hidePopup()
    }

}

@OptIn(DelicateCoroutinesApi::class)
class DebouncingPopupProvider(private val provider: () -> PopupProvider) : PopupProvider {
    private val DEFAULT_POPUP_DELAY = 200L
    private val defaultPopupDelay: Long
        get() = when (val delay = injector.variableService.getGlobalVariableValue("WhichKey_DefaultDelay")) {
            null -> DEFAULT_POPUP_DELAY
            !is VimInt -> DEFAULT_POPUP_DELAY
            else -> delay.value.toLong()
        }
    private var debounceJob: Job? = null
    override fun showPopup(
        editor: Editor,
        typedKeys: List<KeyStroke>,
        nestedMappings: List<Pair<String, Mapping>>,
        startTime: Long
    ) {
        /*
         * wait for a few ms before showing the Balloon to prevent flickering on fast consecutive key presses
         * subtract the already passed time (for calculations etc.) to make the delay as consistent as possible
         */
        val delay = (defaultPopupDelay - (System.currentTimeMillis() - startTime)).coerceAtLeast(0)
        when (delay) {
            0L -> provider().showPopup(editor, typedKeys, nestedMappings, startTime)
            else -> {
                debounceJob = GlobalScope.launch {
                    delay(delay)
                    withContext(Dispatchers.EDT) {
                        provider().showPopup(editor, typedKeys, nestedMappings, startTime)
                    }
                }
            }
        }
    }

    override fun hidePopup() {
        runBlocking {
            debounceJob?.cancelAndJoin()
        }
        provider().hidePopup()
    }

}