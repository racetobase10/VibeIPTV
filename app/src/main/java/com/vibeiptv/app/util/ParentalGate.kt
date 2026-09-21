package com.vibeiptv.app.util

import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vibeiptv.app.data.repo.PortalStore

/**
 * Parental lock gate. If no PIN is set, or the category is not locked,
 * [onUnlocked] runs immediately. Otherwise a numeric 4-digit PIN dialog is shown.
 */
object ParentalGate {
    fun check(activity: AppCompatActivity, categoryId: String, onUnlocked: () -> Unit) {
        val store = PortalStore(activity)
        val pin = store.getPin()
        if (pin == null || categoryId !in store.getLockedCategoryIds()) {
            onUnlocked()
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "••••"
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Parental PIN")
            .setMessage("This category is locked.")
            .setView(input)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        // Override positive click so a wrong PIN doesn't dismiss + lets us toast.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString() == pin) {
                dlg.dismiss()
                onUnlocked()
            } else {
                Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }
        input.requestFocus()
    }
}
