package com.example.myapplication.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import com.example.myapplication.R
import com.example.myapplication.model.SimulationSession
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText

class CreateSessionBottomSheet(
    private val onSessionCreated: (SimulationSession) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_create_session, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val editSessionName = view.findViewById<TextInputEditText>(R.id.editSessionName)
        val editAttackerPhone = view.findViewById<TextInputEditText>(R.id.editAttackerPhone)
        val radioGroupSim = view.findViewById<RadioGroup>(R.id.radioGroupSim)
        val editNotes = view.findViewById<TextInputEditText>(R.id.editNotes)
        val buttonCreate = view.findViewById<Button>(R.id.buttonCreateSession)

        buttonCreate.setOnClickListener {
            val name = editSessionName.text?.toString()?.trim() ?: ""
            val phone = editAttackerPhone.text?.toString()?.trim() ?: ""
            val notes = editNotes.text?.toString()?.trim() ?: ""
            
            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(requireContext(), "Name and Phone are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Determine if the input is a phone number or a Sender ID
            // If it contains any alphabets, we treat it as a Sender ID (e.g., VM-KOTAK)
            val hasLetters = phone.any { it.isLetter() }
            val attackerPhone = if (hasLetters) "" else phone
            val attackerSenderId = if (hasLetters) phone else ""

            val subId = when (radioGroupSim.checkedRadioButtonId) {
                R.id.radioSim1 -> 1
                R.id.radioSim2 -> 2
                else -> -1
            }

            val newSession = SimulationSession(
                name = name,
                attackerPhone = attackerPhone.ifEmpty { "" },
                attackerSenderId = attackerSenderId,
                subscriptionId = subId,
                notes = notes
            )
            
            onSessionCreated(newSession)
            dismiss()
        }
    }
}
