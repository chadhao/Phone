package dev.chadhao.phone.helpers

import android.content.Context
import dev.chadhao.phone.extensions.config
import dev.chadhao.phone.extensions.numberForNotes
import dev.chadhao.phone.models.CallerNote
import com.google.gson.Gson
import java.util.Calendar
import java.util.Locale

class CallerNotesHelper(val context: Context) {

    fun addCallerNotes(number: String, note: String, callerNote: CallerNote?, callback: () -> Unit = {}) {
        val date = Calendar.getInstance(Locale.ENGLISH).timeInMillis
        val mCallerNotes = context.config.parseCallerNotes()
        mCallerNotes.remove(callerNote)
        mCallerNotes.add(CallerNote(number.numberForNotes(), note, date))
        context.config.callerNotes = Gson().toJson(mCallerNotes)
        callback.invoke()
    }

    fun deleteCallerNotes(callerNote: CallerNote?, callback: () -> Unit = {}) {
        val mCallerNotes = context.config.parseCallerNotes()
        mCallerNotes.remove(callerNote)
        context.config.callerNotes = Gson().toJson(mCallerNotes)
        callback.invoke()
    }

    fun removeCallerNotes(allRecentsNumber: List<String>) {
        val mCallerNotes = context.config.parseCallerNotes()
        val newList = mCallerNotes.filter { allRecentsNumber.contains(it.id.numberForNotes()) }
        if (mCallerNotes != newList) context.config.callerNotes = Gson().toJson(newList)
    }

    fun getCallerNotes(number: String?): CallerNote? {
        val mCallerNotes = context.config.parseCallerNotes()
        return mCallerNotes.firstOrNull {it.id.numberForNotes() == number?.numberForNotes()}
    }
}
