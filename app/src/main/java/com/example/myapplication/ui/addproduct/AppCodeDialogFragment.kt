package com.example.myapplication.ui.addproduct

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.myapplication.R
import com.example.myapplication.util.PreferencesManager

class AppCodeDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireActivity())
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_appcode, null)
        val editTextAppCode = view.findViewById<EditText>(R.id.editTextAppCode)
        
        // Get current app code if available
        val preferencesManager = PreferencesManager.getInstance(requireContext())
        val currentAppCode = preferencesManager.getBarcodeAppCode()
        if (!currentAppCode.isNullOrEmpty()) {
            editTextAppCode.setText(currentAppCode)
        }
        
        builder.setTitle("Enter API APPCODE")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val appCode = editTextAppCode.text.toString().trim()
                if (appCode.isNotEmpty()) {
                    preferencesManager.saveBarcodeAppCode(appCode)
                    
                    // If we're in AddProductActivity, try to look up the barcode
                    val activity = activity
                    if (activity is AddProductActivity) {
                        val barcode = activity.findViewById<EditText>(R.id.editTextBarcode)?.text?.toString()
                        if (!barcode.isNullOrEmpty() && barcode.startsWith("69") && barcode.length == 13) {
                            activity.lookupBarcodeInfo(barcode)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
        
        return builder.create()
    }
} 