package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.filter.FilterResult
import com.example.myapplication.filter.SmsPreFilter
import com.example.myapplication.preprocessor.SmsPreprocessor
import com.example.myapplication.trigger.TriggerEngine
import com.example.myapplication.model.IncomingSms
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        resultText = findViewById(R.id.resultText)
        val testButton = findViewById<Button>(R.id.testButton)
        val simulationButton = findViewById<Button>(R.id.simulationButton)
        val evidenceButton = findViewById<Button>(R.id.evidenceButton)

        requestSmsPermissions()

        testButton.setOnClickListener {
            runPipelineTest()
        }

        simulationButton.setOnClickListener {
            startActivity(android.content.Intent(this, com.example.myapplication.ui.SessionListActivity::class.java))
        }

        evidenceButton.setOnClickListener {
            startActivity(android.content.Intent(this, com.example.myapplication.ui.EvidenceActivity::class.java))
        }
    }

    private fun runPipelineTest() {
        lifecycleScope.launch {

            val output = StringBuilder()

            // --- TEST 1: Pre-filter ---
            output.appendLine("=== FILTER TEST ===")

            val otpSms = IncomingSms("VM-HDFCBK", "Your OTP is 123456. Valid for 10 minutes.")
            val filterResult1 = SmsPreFilter.shouldIgnore(otpSms.sender, otpSms.body)
            output.appendLine("OTP from DLT sender → $filterResult1 (expected: IGNORE)")

            val scamSms = IncomingSms("+919876543210",
                "Dear customer your KYC is expired. Account will be blocked. Update now: http://fake-rbi.com")
            val filterResult2 = SmsPreFilter.shouldIgnore(scamSms.sender, scamSms.body)
            output.appendLine("Scam from mobile → $filterResult2 (expected: PROCESS)")

            val spoofOtp = IncomingSms("+918888777766",
                "Your OTP is 9988. Share with our agent to verify your account.")
            val filterResult3 = SmsPreFilter.shouldIgnore(spoofOtp.sender, spoofOtp.body)
            output.appendLine("OTP spoof from mobile → $filterResult3 (expected: SUSPICIOUS_OTP_SPOOF)")

            // --- TEST 2: Preprocessor ---
            output.appendLine("\n=== PREPROCESSOR TEST ===")
            val preprocessed = SmsPreprocessor.process(scamSms)
            output.appendLine("Normalized: ${preprocessed.normalizedText}")
            output.appendLine("URLs found: ${preprocessed.extractedEntities.urls}")
            output.appendLine("Sender type: ${preprocessed.senderType}")
            output.appendLine("Feature vector size: ${preprocessed.featureVector.size}")

            // --- TEST 3: Trigger Engine ---
            output.appendLine("\n=== TRIGGER ENGINE TEST ===")
            val triggerResult = TriggerEngine.evaluate(preprocessed)
            output.appendLine("Should process: ${triggerResult.shouldProcess}")
            output.appendLine("Fired triggers: ${triggerResult.firedTriggers}")
            output.appendLine("Priority: ${triggerResult.priority}")

            // Display results
            resultText.text = output.toString()
        }
    }

    private fun requestSmsPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 100)
        }
    }
}