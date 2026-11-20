package com.picstory.app

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * New highlight: name it, pick from YOUR stories — active ones from the
 * server plus expired ones from the local record (Prefs.myStories).
 */
class CreateHighlightActivity : Activity() {
    private data class PickableStory(
        val id: Int,
        val imageUrl: String,
        val label: String,
        var selected: Boolean = false
    )

    private var stories: List<PickableStory> = emptyList()
    private var saving = false
    private lateinit var etName: EditText
    private lateinit var btnSave: Button
    private lateinit var tvEmpty: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = stories.size
        override fun getItem(p: Int) = stories[p]
        override fun getItemId(p: Int) = stories[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@CreateHighlightActivity)
                .inflate(R.layout.item_pick_story, parent, false)
            val s = stories[pos]
            val iv = v.findViewById<ImageView>(R.id.ivThumb)
            val tvLabel = v.findViewById<TextView>(R.id.tvLabel)
            val cb = v.findViewById<CheckBox>(R.id.cbSelect)
            tvLabel.text = s.label
            cb.isChecked = s.selected
            if (s.imageUrl.isNotEmpty()) {
                ImageLoader.load(s.imageUrl, iv, 300)
            } else {
                iv.setImageBitmap(null)
            }
            // Whole row toggles; the checkbox itself is not focusable.
            val toggle = {
                s.selected = !s.selected
                notifyDataSetChanged()
            }
            v.setOnClickListener { toggle() }
            cb.setOnClickListener { toggle() }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_create_highlight)

        etName = findViewById(R.id.etName)
        btnSave = findViewById(R.id.btnSaveHighlight)
        tvEmpty = findViewById(R.id.tvEmpty)
        val lv = findViewById<ListView>(R.id.lvStories)
        lv.adapter = adapter

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        btnSave.setOnClickListener { save() }
        loadStories()
    }

    private fun loadStories() {
        // Active stories from the server (own group first).
        Api.callArray("GET", "/api/stories") { ok, arr, _ ->
            runOnUiThread {
                val active = mutableListOf<PickableStory>()
                if (ok && arr != null) {
                    for (i in 0 until arr.length()) {
                        try {
                            val g = StoryGroup.fromJson(arr.getJSONObject(i))
                            if (g.user.id == Prefs.myUserId) {
                                for (s in g.stories) {
                                    active.add(
                                        PickableStory(
                                            s.id, s.imageUrl,
                                            if (s.videoUrl.isNotEmpty())
                                                "🎬 Video • ${Ui.timeAgo(s.createdAt)}"
                                            else "Story • ${Ui.timeAgo(s.createdAt)}"
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                // Expired ones only exist in the local record.
                val activeIds = active.map { it.id }.toSet()
                val expired = Prefs.myStories()
                    .filter { it.id !in activeIds }
                    .map {
                        PickableStory(
                            it.id, it.imageUrl,
                            if (it.videoUrl.isNotEmpty())
                                "🎬 Video • Expired • ${Ui.timeAgo(it.createdAt)}"
                            else "Expired • ${Ui.timeAgo(it.createdAt)}"
                        )
                    }
                stories = active + expired
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (stories.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun save() {
        if (saving) return
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            Ui.toast(this, "Give your highlight a name")
            return
        }
        val ids = stories.filter { it.selected }.map { it.id }
        if (ids.isEmpty()) {
            Ui.toast(this, "Pick at least one story")
            return
        }
        saving = true
        btnSave.isEnabled = false
        btnSave.text = "Saving…"
        val body = JSONObject().apply {
            put("name", name)
            put("story_ids", JSONArray(ids))
        }
        Api.call("POST", "/api/highlights", body) { ok, _, err ->
            runOnUiThread {
                saving = false
                btnSave.isEnabled = true
                btnSave.text = getString(R.string.save_highlight)
                if (ok) {
                    Ui.toast(this, "Highlight saved!")
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Ui.toast(this, err ?: "Could not save highlight")
                }
            }
        }
    }
}
