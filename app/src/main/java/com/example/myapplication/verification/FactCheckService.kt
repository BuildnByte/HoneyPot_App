package com.example.myapplication.verification

import android.content.Context
import android.util.Log
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.preprocessor.SmsPreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

data class FactCheckResult(
    val indicatorType: String,      // "DOMAIN", "UPI_ID", "PHONE", "ACCOUNT"
    val indicatorValue: String,
    val verdict: String,            // "MALICIOUS", "SAFE", "UNVERIFIED"
    val confidence: Float,          // 0.0-1.0
    val source: String              // e.g. "safe_browsing", "local_kb", "prior_evidence"
)

class FactCheckService(private val context: Context) {

    private val dao by lazy {
        KnowledgeBaseDatabase.getInstance(context).knowledgeBaseDao()
    }

    // 1. Check local indicator KB first (fast, offline, no network dependency)
    suspend fun checkLocalIndicator(value: String): FactCheckResult? = withContext(Dispatchers.IO) {
        val entry = dao.getIndicator(value)
        if (entry != null) {
            FactCheckResult(
                indicatorType = entry.indicatorType,
                indicatorValue = entry.indicatorValue,
                verdict = when (entry.verdict) {
                    "KNOWN_MALICIOUS" -> "MALICIOUS"
                    "KNOWN_SAFE" -> "SAFE"
                    else -> "UNVERIFIED"
                },
                confidence = entry.confidence,
                source = "local_kb"
            )
        } else null
    }

    // 2. Check against previously extracted scam evidence in this app's own DB
    suspend fun checkPriorEvidence(value: String): FactCheckResult? = withContext(Dispatchers.IO) {
        val matches = dao.getEvidenceMatchingValue(value)
        if (matches.isNotEmpty()) {
            val matchCount = matches.size
            FactCheckResult(
                indicatorType = matches.first().entityType,
                indicatorValue = value,
                verdict = "MALICIOUS",
                confidence = min(1.0f, 0.5f + 0.1f * matchCount),
                source = "prior_evidence"
            )
        } else null
    }

    // 3. Check domain reputation via Google Safe Browsing API
    suspend fun checkDomainReputation(domain: String): FactCheckResult = withContext(Dispatchers.IO) {
        val apiKey = com.example.myapplication.BuildConfig.SAFE_BROWSING_API_KEY

        if (apiKey.isBlank()) {
            return@withContext FactCheckResult("DOMAIN", domain, "UNVERIFIED", 0.0f, "safe_browsing_key_not_configured")
        }

        try {
            val url = URL("https://safebrowsing.googleapis.com/v4/threatMatches:find?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000 // 5 seconds
            conn.readTimeout = 5000    // 5 seconds
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientId", "honeypot-app")
                    put("clientVersion", "1.0")
                })
                put("threatInfo", JSONObject().apply {
                    put("threatTypes", JSONArray(listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE")))
                    put("platformTypes", JSONArray(listOf("ANY_PLATFORM")))
                    put("threatEntryTypes", JSONArray(listOf("URL")))
                    put("threatEntries", JSONArray(listOf(JSONObject().apply { put("url", domain) })))
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val isMalicious = jsonResponse.has("matches") && jsonResponse.getJSONArray("matches").length() > 0
                
                if (isMalicious) {
                    FactCheckResult("DOMAIN", domain, "MALICIOUS", 0.9f, "safe_browsing")
                } else {
                    FactCheckResult("DOMAIN", domain, "SAFE", 0.7f, "safe_browsing")
                }
            } else {
                Log.e("FactCheckService", "Safe Browsing API error: ${conn.responseCode}")
                FactCheckResult("DOMAIN", domain, "UNVERIFIED", 0.0f, "safe_browsing_api_error")
            }
        } catch (e: Exception) {
            Log.e("FactCheckService", "Network error checking domain reputation", e)
            FactCheckResult("DOMAIN", domain, "UNVERIFIED", 0.0f, "safe_browsing_network_error")
        }
    }

    // 4. Orchestrator
    suspend fun verifyEntities(
        entities: SmsPreprocessor.Entities
    ): List<FactCheckResult> = coroutineScope {
        val allValues = (entities.upiIds + entities.phoneNumbers + entities.accountNumbers + entities.urls).distinct()
        
        val localChecks = allValues.map { value ->
            async { checkLocalIndicator(value) }
        }
        
        val evidenceChecks = allValues.map { value ->
            async { checkPriorEvidence(value) }
        }
        
        val domainChecks = entities.urls.map { url ->
            async { 
                // Safe Browsing evaluates exact URLs (including paths), not just root domains.
                // e.g. bit.ly/malicious is dangerous, but bit.ly root is safe.
                checkDomainReputation(url)
            }
        }

        val results = mutableListOf<FactCheckResult>()
        
        (localChecks.awaitAll().filterNotNull() + 
         evidenceChecks.awaitAll().filterNotNull() + 
         domainChecks.awaitAll()).forEach { results.add(it) }

        results
    }
}
