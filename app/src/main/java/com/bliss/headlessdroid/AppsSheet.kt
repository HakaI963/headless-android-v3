package com.bliss.headlessdroid

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * The app picker: every launchable app on the phone, one dense list.
 *
 * <p>Nothing here clones or copies an app. The list is read straight from the
 * PackageManager, and launching hands the package straight to the engine.
 */
internal object AppsSheet {

    fun open(activity: Activity, engine: Engine) {
        val dialog = Dialog(activity)
        dialog.setContentView(R.layout.apps)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setCancelable(true)

        val list = dialog.findViewById<ListView>(R.id.apps_list)
        val empty = dialog.findViewById<TextView>(R.id.apps_empty)
        val search = dialog.findViewById<EditText>(R.id.apps_search)
        list.emptyView = empty

        val adapter = Adapter(engine.apps())
        list.adapter = adapter
        // The installed-app list is built off the UI thread; refresh the sheet
        // when it lands instead of showing an empty grid of apps.
        val reload = Runnable {
            adapter.replace(engine.apps())
            adapter.filter(search.text.toString())
        }
        engine.addListener(reload)
        dialog.setOnDismissListener { engine.removeListener(reload) }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val entry = adapter.item(position)
            dialog.dismiss()
            val existing = engine.tileForPkg(entry.pkg)
            if (existing != null) {
                engine.restart(existing.slot)
                return@OnItemClickListener
            }
            if (engine.freeSlots() <= 0) {
                Toast.makeText(activity, "Grid is full (9 apps)", Toast.LENGTH_SHORT).show()
                return@OnItemClickListener
            }
            engine.launch(entry.pkg)
        }
        dialog.show()
    }

    private class Adapter(entries: List<Engine.AppEntry>) : BaseAdapter() {

        private val all = ArrayList(entries)
        private var shown = ArrayList(entries)

        fun replace(entries: List<Engine.AppEntry>) {
            all.clear()
            all.addAll(entries)
        }

        fun filter(query: String) {
            val needle = query?.trim()?.lowercase(Locale.US) ?: ""
            val filtered = ArrayList<Engine.AppEntry>()
            for (entry in all) {
                if (needle.isEmpty()
                        || entry.label.lowercase(Locale.US).contains(needle)
                        || entry.pkg.lowercase(Locale.US).contains(needle)) {
                    filtered.add(entry)
                }
            }
            shown = filtered
            notifyDataSetChanged()
        }

        fun item(position: Int): Engine.AppEntry = shown[position]

        override fun getCount(): Int = shown.size

        override fun getItem(position: Int): Any? = shown[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val view = convert ?: LayoutInflater.from(parent.context)
                    .inflate(R.layout.row_app, parent, false)
            val entry = shown[position]
            view.findViewById<TextView>(R.id.row_label).text = entry.label
            view.findViewById<TextView>(R.id.row_package).text = entry.pkg
            return view
        }
    }
}