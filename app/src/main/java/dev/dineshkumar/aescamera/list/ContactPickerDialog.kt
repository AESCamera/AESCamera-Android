package dev.dineshkumar.aescamera.list

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.dineshkumar.aescamera.R

abstract class ContactPickerDialog(context: Context, private var list: List<ContactItem>, private val onContactSelected: (ContactItem) -> Unit) :
    Dialog(context) {
    private var adapter: ContactAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = layoutInflater.inflate(R.layout.dialog_contacts, null)
        setContentView(view)

        setCanceledOnTouchOutside(true)
        setCancelable(true)

        setupRecyclerView(view)
        setupSearchView(view)
    }

    private fun setupRecyclerView(view: View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.rvList)
        adapter = ContactAdapter(context, list, onContactSelected = {
            onContactSelected(it)
            dismiss()
        })
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)
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
    }
}