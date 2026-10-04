package com.aali.ebookreader

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.prefsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)

            findPreference<Preference>("gemini_model")?.summary =
                "Now: " + Prefs.model(requireContext())

            findPreference<Preference>("pick_model")?.setOnPreferenceClickListener {
                pickModel()
                true
            }

            findPreference<Preference>("urdu_voice_install")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                try {
                    // the phone's own screen for downloading text-to-speech voices
                    startActivity(
                        android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS"))
                    } catch (e2: Exception) {
                        Toast.makeText(
                            ctx,
                            "Open Settings → Accessibility → Text-to-speech, then download the Urdu voice",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }

            findPreference<Preference>("open_stats")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), StatsActivity::class.java))
                true
            }
        }

        /** Asks Google which models this key can use, then lets the user choose. */
        private fun pickModel() {
            val ctx = requireContext()
            val key = Prefs.apiKey(ctx)
            if (key.isEmpty()) {
                Toast.makeText(ctx, "Add your Gemini API key first", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(ctx, "Asking Google for the model list…", Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val models = GeminiClient.listModels(key)
                    activity?.runOnUiThread {
                        if (models.isEmpty()) {
                            Toast.makeText(ctx, "No usable models found", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        val current = Prefs.model(ctx)
                        val labels = models.map {
                            if (it.id == current) "✓ ${it.label}  (${it.id})"
                            else "${it.label}  (${it.id})"
                        }.toTypedArray()
                        Ui.builder(requireActivity())
                            .setTitle("Choose AI model")
                            .setItems(labels) { _, which ->
                                Prefs.setModel(ctx, models[which].id)
                                findPreference<Preference>("gemini_model")?.summary =
                                    "Now: " + models[which].id
                                Toast.makeText(
                                    ctx, "Using ${models[which].id}", Toast.LENGTH_SHORT
                                ).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }
}
