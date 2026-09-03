package dev.chadhao.phone.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.viewBinding
import dev.chadhao.phone.activities.SimpleActivity
import dev.chadhao.phone.adapters.RecentCallsAdapter
import dev.chadhao.phone.databinding.DialogShowGroupedCallsBinding
import dev.chadhao.phone.models.RecentCall

class ShowGroupedCallsDialog(val activity: BaseSimpleActivity, recentCalls: List<RecentCall>) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogShowGroupedCallsBinding::inflate)

    init {
        activity.runOnUiThread {
            RecentCallsAdapter(
                activity = activity as SimpleActivity,
                recyclerView = binding.selectGroupedCallsList,
                refreshItemsListener = null,
                showOverflowMenu = false,
                itemClick = {}
            ).apply {
                binding.selectGroupedCallsList.adapter = this
                updateItems(recentCalls)
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
