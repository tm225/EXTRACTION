package com.marius.apkextractor

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class AppItem(
        val label: String,
        val pkg: String,
        val version: String,
        val icon: Drawable,
        val info: ApplicationInfo,
        val isSystem: Boolean
    )

    private var allApps = listOf<AppItem>()
    private val shown = mutableListOf<AppItem>()
    private lateinit var adapter: ListAdapterImpl
    private lateinit var search: EditText
    private lateinit var systemBox: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(0))
        }
        search = EditText(this).apply {
            hint = "Rechercher une application…"
            setSingleLine()
        }
        systemBox = CheckBox(this).apply { text = "Afficher les applications système" }
        val list = ListView(this)
        adapter = ListAdapterImpl()
        list.adapter = adapter

        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(systemBox, LinearLayout.LayoutParams(-1, -2))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        systemBox.setOnCheckedChangeListener { _, _ -> applyFilter() }

        list.setOnItemClickListener { _, _, pos, _ ->
            val app = shown[pos]
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("Extraire l'APK de cette application vers Téléchargements/APK_Extraits ?")
                .setPositiveButton("Extraire") { _, _ -> extract(app) }
                .setNegativeButton("Annuler", null)
                .show()
        }

        loadApps()
    }

    private fun loadApps() {
        thread {
            val pm = packageManager
            val items = pm.getInstalledApplications(0).map { ai ->
                val version = try {
                    pm.getPackageInfo(ai.packageName, 0).versionName ?: "?"
                } catch (e: Exception) { "?" }
                AppItem(
                    label = pm.getApplicationLabel(ai).toString(),
                    pkg = ai.packageName,
                    version = version,
                    icon = pm.getApplicationIcon(ai),
                    info = ai,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.sortedBy { it.label.lowercase() }
            runOnUiThread {
                allApps = items
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val withSystem = systemBox.isChecked
        shown.clear()
        shown.addAll(allApps.filter {
            (withSystem || !it.isSystem) &&
                (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
        })
        adapter.notifyDataSetChanged()
    }

    private fun extract(app: AppItem) {
        Toast.makeText(this, "Extraction en cours…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val safeName = app.label.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val files = mutableListOf<Pair<File, String>>()
                files.add(File(app.info.sourceDir) to "${safeName}_${app.version}.apk")
                app.info.splitSourceDirs?.forEach { path ->
                    val f = File(path)
                    files.add(f to "${safeName}_${app.version}_${f.name}")
                }
                files.forEach { (src, name) -> copyToDownloads(src, name) }
                val msg = if (files.size > 1)
                    "${files.size} fichiers APK (split) enregistrés dans Téléchargements/APK_Extraits"
                else "APK enregistré dans Téléchargements/APK_Extraits"
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyToDownloads(src: File, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/APK_Extraits")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Impossible de créer le fichier")
        contentResolver.openOutputStream(uri)!!.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
    }

    inner class ListAdapterImpl : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = shown[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val icon = ImageView(this@MainActivity).apply { setImageDrawable(app.icon) }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            texts.addView(TextView(this@MainActivity).apply {
                text = app.label
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(this@MainActivity).apply {
                text = "${app.pkg}  •  v${app.version}"
                textSize = 12f
                setTextColor(0xFF666666.toInt())
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }
    }
}
