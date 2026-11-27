package com.mpedreira.nimwifi

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mpedreira.nimwifi.databinding.ActivityConfigurationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imported configuration on the device: roaming probe, network tests, HTTP scripts and the names
 * of stored credentials (values are never shown). Import a JSON, HAR or configuration ZIP, export
 * the current configuration (without credentials), edit or remove items.
 */
class ConfigurationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConfigurationBinding
    private val store by lazy { WiproberConfigStore(applicationContext) }
    private val throughputStore by lazy { ThroughputSettingsStore(applicationContext) }
    private val guideStore by lazy { SurveyGuideSettingsStore(applicationContext) }
    private val importer by lazy { ConfigurationImport(this) { render() } }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.import(uri)
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigurationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.configurationRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.configurationToolbar.setNavigationOnClickListener { finish() }
        render()
    }

    private fun render() {
        val content = binding.configurationContent
        content.removeAllViews()
        val imported = store.load()
        val credentials = store.credentialNames()

        content.addView(note(getString(R.string.config_screen_hint)))
        content.addView(buttons(
            getString(R.string.config_import) to { openFile.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*")) },
            getString(R.string.config_export) to { exportFile.launch("wiprober-config.json") },
            getString(R.string.config_remove) to ::confirmRemoveAll
        ))

        // Roaming probe
        content.addView(header(getString(R.string.config_roaming)))
        val roaming = imported.roamingSettings
        content.addView(switchRow(getString(R.string.config_roaming_enabled), roaming.enabled) { on ->
            store.update { it.copy(roaming = it.roamingSettings.copy(enabled = on)) }
        })
        content.addView(row(
            if (roaming.host.isEmpty()) getString(R.string.config_roaming_no_target)
            else getString(R.string.config_roaming_target, roaming.host, roaming.port, roaming.intervalMillis),
            getString(R.string.config_edit) to ::editRoaming
        ))

        // Network tests
        content.addView(header(getString(R.string.config_tests_header, imported.tests.orEmpty().size)))
        imported.tests.orEmpty().forEach { test ->
            val target = test.url ?: listOfNotNull(test.host, test.port?.toString()).joinToString(":")
            content.addView(switchRow("${test.displayAlias} · ${test.type} · $target", test.enabled) { on ->
                store.update { it.copy(tests = it.tests.orEmpty().map { t -> if (t.name == test.name) t.copy(enabled = on) else t }) }
            })
            content.addView(row(null,
                getString(R.string.config_edit) to { editTest(test) },
                getString(R.string.config_delete) to { confirmDelete(test.displayAlias) { store.update { it.copy(tests = it.tests.orEmpty().filterNot { t -> t.name == test.name }) } } }
            ))
        }
        content.addView(buttons(getString(R.string.config_add_test) to { editTest(null) }))

        // Scripts
        content.addView(header(getString(R.string.config_scripts_header, imported.scripts.orEmpty().size)))
        imported.scripts.orEmpty().forEach { script ->
            val writes = script.steps.count { it.isWrite }
            content.addView(switchRow(
                getString(R.string.config_script_line, script.displayAlias, script.steps.size, writes), script.enabled
            ) { on ->
                store.update { it.copy(scripts = it.scripts.orEmpty().map { s -> if (s.name == script.name) s.copy(enabled = on) else s }) }
            })
            content.addView(row(null,
                getString(R.string.config_view_steps) to { showSteps(script) },
                getString(R.string.config_delete) to { confirmDelete(script.displayAlias) { store.update { it.copy(scripts = it.scripts.orEmpty().filterNot { s -> s.name == script.name }) } } }
            ))
        }
        if (imported.scripts.isNullOrEmpty()) content.addView(note(getString(R.string.config_scripts_empty)))

        // Credentials: names only
        val needed = imported.scripts.orEmpty().flatMap { it.credentialReferences() }.toSortedSet()
        content.addView(header(getString(R.string.config_credentials_header, credentials.size)))
        content.addView(note(getString(R.string.config_credentials_hint)))
        (credentials + needed).toSortedSet().forEach { name ->
            val state = getString(if (name in credentials) R.string.config_credential_set else R.string.config_credential_missing)
            content.addView(row("$name · $state",
                getString(R.string.config_set_value) to { editCredential(name) },
                *(if (name in credentials) arrayOf(getString(R.string.config_delete) to { confirmDelete(name) { store.removeCredential(name) } }) else emptyArray())
            ))
        }
        content.addView(buttons(getString(R.string.config_add_credential) to { editCredential(null) }))
    }

    private fun editRoaming() {
        val current = store.load().roamingSettings
        val form = Form()
        val host = form.text(getString(R.string.config_field_host), current.host)
        val port = form.number(getString(R.string.config_field_port), current.port.toString())
        val interval = form.number(getString(R.string.config_field_interval), current.intervalMillis.toString())
        form.show(getString(R.string.config_roaming)) {
            val settings = RoamingSettings(
                enabled = current.enabled,
                host = host.value(),
                port = port.value().toIntOrNull() ?: current.port,
                intervalMillis = interval.value().toIntOrNull() ?: current.intervalMillis
            ).sanitized()
            store.update { it.copy(roaming = settings) }
        }
    }

    private fun editTest(existing: NetworkTest?) {
        val form = Form()
        val name = form.text(getString(R.string.config_field_name), existing?.name.orEmpty(), enabled = existing == null)
        val alias = form.text(getString(R.string.config_field_alias), existing?.alias.orEmpty())
        val type = form.choice(getString(R.string.config_field_type), NetworkTest.TYPES.sorted(), existing?.type ?: NetworkTest.TCP)
        val host = form.text(getString(R.string.config_field_host_hint), existing?.host.orEmpty())
        val port = form.number(getString(R.string.config_field_port), existing?.port?.toString().orEmpty())
        val url = form.text(getString(R.string.config_field_url), existing?.url.orEmpty())
        val count = form.number(getString(R.string.config_field_count), existing?.count?.toString().orEmpty())
        form.show(getString(if (existing == null) R.string.config_add_test else R.string.config_edit)) {
            val json = com.google.gson.JsonObject().apply {
                addProperty("format", WiproberConfigParser.FORMAT)
                addProperty("version", WiproberConfigParser.VERSION)
                add("tests", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonObject().apply {
                        addProperty("name", name.value())
                        addProperty("alias", alias.value().ifEmpty { null })
                        addProperty("type", type.selectedItem as String)
                        addProperty("host", host.value().ifEmpty { null })
                        port.value().toIntOrNull()?.let { addProperty("port", it) }
                        addProperty("url", url.value().ifEmpty { null })
                        count.value().toIntOrNull()?.let { addProperty("count", it) }
                        addProperty("enabled", existing?.enabled ?: true)
                    })
                })
            }
            // Same validation as an imported file.
            val test = WiproberConfigParser.parse(json.toString(), "test").tests!!.single()
            store.update { imported ->
                val others = imported.tests.orEmpty().filterNot { it.name == test.name }
                if (existing == null && others.size != imported.tests.orEmpty().size) throw ConfigException("Test names must be unique")
                if (others.size + 1 > WiproberConfigParser.MAX_TESTS) throw ConfigException("At most ${WiproberConfigParser.MAX_TESTS} tests")
                imported.copy(tests = imported.tests.orEmpty().map { if (it.name == test.name) test else it }.let { list ->
                    if (list.any { it.name == test.name }) list else list + test
                })
            }
        }
    }

    private fun showSteps(script: HttpScript) {
        val text = script.steps.mapIndexed { i, step ->
            "${i + 1}. ${step.name}\n   ${step.method} ${step.url.substringBefore('?')}" +
                (if (step.isWrite) "  ⚠ ${getString(R.string.config_write)}" else "")
        }.joinToString("\n")
        MaterialAlertDialogBuilder(this).setTitle(script.displayAlias).setMessage(text).setPositiveButton(R.string.action_ok, null).show()
    }

    private fun editCredential(existing: String?) {
        val form = Form()
        val name = form.text(getString(R.string.config_field_name), existing.orEmpty(), enabled = existing == null)
        val value = form.text(getString(R.string.config_field_value), "", secret = true)
        form.show(existing ?: getString(R.string.config_add_credential)) {
            store.setCredential(name.value(), value.field.text?.toString().orEmpty())
        }
    }

    private fun confirmDelete(what: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.config_delete_confirm, what))
            .setPositiveButton(R.string.config_delete) { _, _ -> action(); render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveAll() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.config_remove_confirm)
            .setPositiveButton(R.string.config_remove) { _, _ ->
                store.clear()
                Toast.makeText(this, R.string.config_removed, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export(uri: android.net.Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = ConfigExport.toJson(store.load(), throughputStore.load(), guideStore.load(), store.credentialNames())
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot open the destination" }.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            Toast.makeText(
                this@ConfigurationActivity,
                if (result.isSuccess) getString(R.string.config_exported) else getString(R.string.config_export_failed, result.exceptionOrNull()?.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // --- small view builders -----------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        this.text = text
        isChecked = checked
        minHeight = dp(48)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun row(text: String?, vararg actions: Pair<String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        if (text != null) addView(TextView(context).apply { this.text = text }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        else addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        actions.forEach { (label, action) -> addView(textButton(label, action)) }
    }

    private fun buttons(vararg actions: Pair<String, () -> Unit>) = com.google.android.material.chip.ChipGroup(this).apply {
        actions.forEach { (label, action) -> addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = label
            setOnClickListener { action() }
        }) }
    }

    private fun textButton(label: String, action: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            setOnClickListener { action() }
        }

    /** A dialog with labelled fields; [onSave] errors are shown and the item is not saved. */
    private inner class Form {
        inner class Field(val field: TextInputEditText) {
            fun value() = field.text?.toString()?.trim().orEmpty()
        }

        private val layout = LinearLayout(this@ConfigurationActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20)) }

        fun text(hint: String, value: String, enabled: Boolean = true, secret: Boolean = false): Field {
            val input = TextInputEditText(this@ConfigurationActivity).apply {
                setText(value)
                isEnabled = enabled
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                isSingleLine = true
            }
            layout.addView(TextInputLayout(this@ConfigurationActivity).apply {
                this.hint = hint
                if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                addView(input)
            })
            return Field(input)
        }

        fun number(hint: String, value: String): Field = text(hint, value).also { it.field.inputType = InputType.TYPE_CLASS_NUMBER }

        fun choice(label: String, options: List<String>, selected: String): Spinner {
            layout.addView(TextView(this@ConfigurationActivity).apply { text = label; setPadding(0, dp(8), 0, 0) })
            return Spinner(this@ConfigurationActivity).apply {
                adapter = ArrayAdapter(this@ConfigurationActivity, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(options.indexOf(selected).coerceAtLeast(0))
                minimumHeight = dp(48)
                layout.addView(this)
            }
        }

        fun show(title: String, onSave: () -> Unit) {
            val dialog = MaterialAlertDialogBuilder(this@ConfigurationActivity)
                .setTitle(title)
                .setView(android.widget.ScrollView(this@ConfigurationActivity).apply { addView(layout) })
                .setPositiveButton(R.string.action_ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching(onSave)
                    .onSuccess { dialog.dismiss(); render() }
                    .onFailure { error ->
                        Toast.makeText(this@ConfigurationActivity, (error as? ConfigException)?.message ?: error.message ?: "", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }
}
