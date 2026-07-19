package dev.dineshkumar.aescamera.list

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.SearchView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.dineshkumar.aescamera.R

abstract class ContactPickerDialog(
    context: Context,
    private var list: List<ContactItem>,
    private val shareCount: Int,
    private val onContactSelected: (ContactItem) -> Unit
) : Dialog(context) {
    private var adapter: ContactAdapter? = null
    private var recyclerView: RecyclerView? = null
    private var emptyView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = layoutInflater.inflate(R.layout.dialog_contacts, null)
        setContentView(view)

        setCanceledOnTouchOutside(true)
        setCancelable(true)

        view.findViewById<TextView>(R.id.tvSubtitle).text =
            context.resources.getQuantityString(
                R.plurals.share_whatsapp_count, shareCount, shareCount
            )

        setupRecyclerView(view)
        setupSearchView(view)
        updateEmptyState(list, null)
    }

    private fun setupRecyclerView(view: View) {
        recyclerView = view.findViewById(R.id.rvList)
        emptyView = view.findViewById(R.id.tvEmpty)
        adapter = ContactAdapter(context, list, onContactSelected = {
            onContactSelected(it)
            dismiss()
        })
        recyclerView?.adapter = adapter
        recyclerView?.layoutManager = LinearLayoutManager(context)
    }

    private fun setupSearchView(view: View) {
        val searchView = view.findViewById<SearchView>(R.id.svSearch)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filter(newText)
                return true
            }
        })
    }

    private fun filter(query: String?) {
        if (adapter == null) return

        val filteredList = query?.let { q ->
            list.filter {
                it.name.contains(q, ignoreCase = true) || it.contactNo.contains(q, ignoreCase = true)
            }
        } ?: list

        adapter?.updateList(filteredList)
        updateEmptyState(filteredList, query)
    }

    private fun updateEmptyState(filteredList: List<ContactItem>, query: String?) {
        val empty = filteredList.isEmpty()
        recyclerView?.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView?.apply {
            visibility = if (empty) View.VISIBLE else View.GONE
            // Distinguish "you have no WhatsApp contacts" from "search matched nothing".
            setText(
                if (query.isNullOrBlank()) R.string.no_contacts_found
                else R.string.no_contacts_match
            )
        }
    }
}
