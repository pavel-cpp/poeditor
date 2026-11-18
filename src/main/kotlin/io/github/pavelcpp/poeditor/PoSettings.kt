package io.github.pavelcpp.poeditor

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/** Application-wide settings: the chosen service and MyMemory email in poeditor.xml, the DeepL key in the IDE's password safe. */
@State(name = "POEditorSettings", storages = [Storage("poeditor.xml")])
class PoSettings : SimplePersistentStateComponent<PoSettings.State>(State()) {
    class State : BaseState() {
        var provider by string(Provider.DEEPL.name)
        var myMemoryEmail by string("")
    }

    private val keyAttributes = CredentialAttributes(generateServiceName("POEditor", "DeepL API key"))

    var provider: Provider
        get() = Provider.entries.firstOrNull { it.name == state.provider } ?: Provider.DEEPL
        set(v) { state.provider = v.name }

    var myMemoryEmail: String
        get() = state.myMemoryEmail.orEmpty()
        set(v) { state.myMemoryEmail = v.trim() }

    var deepLKey: String
        get() = PasswordSafe.instance.getPassword(keyAttributes).orEmpty()
        set(v) = PasswordSafe.instance.setPassword(keyAttributes, v.trim().ifEmpty { null })

    /** Builds the translator for the chosen service (may read the password safe, so not for the UI thread). */
    fun translator(): Translator = when (provider) {
        Provider.DEEPL -> DeepL(deepLKey.ifBlank {
            throw TranslationException("Set the DeepL API key in Settings | Tools | POEditor.")
        })
        Provider.MYMEMORY -> MyMemory(myMemoryEmail)
    }

    companion object {
        fun get() = service<PoSettings>()
    }
}

/** Settings | Tools | POEditor: only the fields of the selected service are shown. */
class PoSettingsConfigurable : Configurable {
    private val provider = ComboBox(Provider.entries.toTypedArray())
    private val deepLKey = JBPasswordField()
    private val email = JBTextField()

    private val deepLPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("API key:", deepLKey)
        .addComponentToRightColumn(BrowserLink("Get a free key (500 000 characters per month)", "https://www.deepl.com/pro-api"))
        .panel
    private val myMemoryPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Email (optional):", email)
        .addTooltip("With an email the free limit is 50 000 characters per day instead of 5 000.")
        .addTooltip("MyMemory does not protect placeholders like %d or {0}, so check the result. Strings over 500 bytes are not supported.")
        .panel

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Translation service:", provider)
        .addComponent(deepLPanel)
        .addComponent(myMemoryPanel)
        .addTooltip("Only the text you ask a suggestion for is sent to the selected service.")
        .addComponentFillVertically(JPanel(), 0)
        .panel

    init {
        provider.addActionListener { showFields() }
    }

    private fun showFields() {
        deepLPanel.isVisible = provider.selectedItem == Provider.DEEPL
        myMemoryPanel.isVisible = provider.selectedItem == Provider.MYMEMORY
    }

    override fun getDisplayName() = "POEditor"
    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean = PoSettings.get().let {
        provider.selectedItem != it.provider || String(deepLKey.password).trim() != it.deepLKey || email.text.trim() != it.myMemoryEmail
    }

    override fun apply() {
        PoSettings.get().let {
            it.provider = provider.selectedItem as Provider
            it.deepLKey = String(deepLKey.password)
            it.myMemoryEmail = email.text
        }
    }

    override fun reset() {
        PoSettings.get().let {
            provider.selectedItem = it.provider
            deepLKey.text = it.deepLKey
            email.text = it.myMemoryEmail
        }
        showFields()
    }
}
