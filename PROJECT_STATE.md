# PROJECT STATE: Agentic AI Honeypot System for Scam Detection

**Repository Path:** `c:\FYP`  
**Current Active Branch:** `stc` (tracking `origin/stc`)  
**Target Platform:** Android (minSdk 26, compileSdk 35, targetSdk 35)  
**Document Updated:** October 2026 (Audit, MuRIL ONNX wiring, and Post-Install Delivery Resolution)  
**Audience:** Contributor / Handoff Assistant (Claude) with no direct filesystem access.

---

## 1. Project Overview

This project is an **Agentic AI Honeypot System for Scam Detection and Evidence Harvesting** implemented as a native Android application in Kotlin. Its primary objective is to protect users from mobile financial fraud (KYC phishing, electricity bill disconnection threats, lottery/prize scams, job fraud, digital arrest/police impersonation, investment scams) by intercepting incoming SMS messages and analyzing cross-app chat screenshots (WhatsApp, Telegram, Instagram DMs). When a scam is detected, the system does not merely alert the user; it activates an agentic honeypot mechanism that can engage the scammer in autonomous or semi-autonomous multi-turn conversations (via local deterministic state progression or cloud LLM generation) designed to safely elicit and extract verifiable attacker identifiers—such as UPI IDs, telephone numbers, bank account numbers, fraudulent URLs, and extortion amounts. All extracted entities and conversation transcripts are cryptographically preserved in an on-device Room database ("Evidence Vault") with cross-session entity correlation, capable of generating formal cybercrime incident PDF reports for submission to law enforcement (e.g., India's National Cyber Crime Reporting Portal at `cybercrime.gov.in`).

### High-Level Architecture Diagram

```text
====================================================================================================
                                      MESSAGE INGESTION LAYER
====================================================================================================
   [Live Incoming SMS]                              [Cross-App Screen Content]
            │                                                    │
            ▼                                                    ▼
   SmsReceiver (BroadcastReceiver)               Quick Settings Tile (ScanQuickSettingsTileService)
            │                                                    │
            ▼                                                    ▼
   SmsProcessingService (Foreground)             ScanOverlayService / HoneypotAccessibilityService
   (Mutex-serialised processing)                 (Silent hardware buffer screenshot / MediaProjection)
            │                                                    │
            │                                                    ▼
            │                                    InPlaceLensController & BlockOverlayView
            │                                    (Bundled ML Kit Latin OCR + MessageSideClassifier)
            │                                                    │
            ▼                                                    ▼
       IncomingSms ◄─────────────────────────────── ManualAnalysisRepository (Synthetic IncomingSms)
            │
====================================================================================================
                                  DETECTION & REASONING PIPELINE
====================================================================================================
            │
            ▼
   Stage 1: SmsPreFilter
            ├── Ignores verified institutional DLT headers (e.g. "VM-HDFCBK" without scam keywords)
            ├── Ignores cached safe contacts
            └── Flags suspicious OTP spoofing & institutional impersonation
            │
            ▼
   Stage 2: SmsPreprocessor
            ├── Regex Entity Extraction: URLs, UPI IDs, Phones, Bank Accounts, Amounts, OTP numbers
            ├── Keyword & structural feature vector synthesis (16 float flags)
            └── Text normalisation (entity token replacement: [URL], [UPI], [PHONE], [AMOUNT], [NUMBER])
            │
            ▼
   Stage 3: Conversation History Assembly
            └── KnowledgeBaseDatabase: loads prior session messages ("[Sender]: body") from SQLite
            │
            ▼
   Stage 4: TriggerEngine (Heuristic Behavioral Analysis)
            ├── Keyword signal evaluation across 6 groups (authority, account threat, credentials, etc.)
            ├── Conversation escalation tracking (multi-turn transition patterns)
            └── Priority classification (LOW, MEDIUM, HIGH, CRITICAL)
            │
            ▼
   Stage 5: VectorSearchEngine & ScamTextEmbedder
            ├── MediaPipe TextEmbedder: universal_sentence_encoder_lite.tflite (CPU delegate, 256-dim)
            └── Cosine similarity search against precomputed KnowledgeBaseEntry embedding blobs
            │
            ▼
   Stage 6: On-Device MuRIL BERT ONNX Classification (OnnxScamClassifier)
            ├── Model: scam_classifier_int8.onnx (ONNX Runtime Mobile 1.19.2, INT8 quantized from 906 MB FP32)
            ├── Tokenizer: BertWordPieceTokenizer with vocab.txt (197,285 tokens, 128 max length, cased)
            ├── Fallback: Heuristic trigger score (CRITICAL: 0.95, HIGH: 0.85, else: 0.65) if model not downloaded
            ├── Stage 6b: Confidence Blending (>=0.90 trusts classifier; else 75% BERT + 25% Trigger score)
            ├── Stage 6c: Scam Subtype Resolution (ScamTypeResolver over triggers and KB vector matches)
            └── Stage 6d: On-Device Named Entity Extraction (ScamEntityExtractor: names, roles, orgs, IFSC, cards)
            │
            ▼
   Stage 7: Multi-Source Fact Checking (FactCheckService)
            ├── Local Indicator KB lookup (IndicatorEntry)
            ├── Prior Captured Evidence matching (cross-session matching in scam_evidence)
            └── Google Safe Browsing API v4 (threatMatches:find HTTP POST over URLs)
            │
            ▼
   Stage 8: Cloud Reasoning & Supplemental Deep NER (CloudReasoningService - First Message Only)
            ├── Model: Google Gemini API (gemini-3.5-flash-lite via OkHttp; Android AI Core is NOT used)
            ├── Returns: Structured 2-3 sentence human-readable rationale + supplementary named entities
            └── Fallback: Local rule/template rationale if offline or key missing
            │
====================================================================================================
                                 EVIDENCE & HONEYPOT DECISION LAYER
====================================================================================================
            │
            ├── IF NOT SCAM:
            │     └── Archive live session or display "SAFE / LOW RISK" in UI
            │
            └── IF CONFIRMED SCAM:
                  │
                  ├── Evidence Persistence:
                  │     └── Writes new ScamEvidence rows (UPI, Phone, Account, URL, Amount, Names/Roles)
                  │         with UNIQUE(sessionId, entityType, entityValue) DB-level deduplication
                  │
                  ├── Honeypot Response Generation:
                  │     ├── Turn 1 (Initial contact) & Turn 2+ (Follow-up attacker messages):
                  │     │     ├── IF user granted cloud consent (ConsentPreferenceManager):
                  │     │     │     └── CloudReplyGenerator: Google Gemini API (gemini-3.5-flash-lite multi-turn)
                  │     │     │
                  │     │     └── IF local only / offline / no consent:
                  │     │           └── ReplyProgressionEngine: deterministic state machine tracking
                  │     │               revealed entities vs. unasked questions per scam category
                  │     │
                  │     └── (Legacy Gemma 3 1B on-device LLM removed; ScamLlmClassifier is a no-op shim)
                  │
                  └── User Presentation & Actuation:
                        ├── SmsProcessingService -> High-priority Notification ("Let AI Handle It")
                        ├── HoneypotConversationActivity -> Real SMS transmission via SmsManager
                        ├── InPlaceLensController -> "Copy Safe Reply" floating chat bubble
                        └── EvidenceDetailActivity & PdfReportGenerator -> Legal cybercrime report
====================================================================================================
```

---

## 2. Current Pipeline Components

### 2.1. Scam Detection & Classification

#### A. On-Device Classifier: MuRIL BERT ONNX (`OnnxScamClassifier.kt`)
- **File Paths:**
  - Classifier: [`app/src/main/java/com/example/myapplication/engine/OnnxScamClassifier.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/OnnxScamClassifier.kt)
  - Tokenizer: [`app/src/main/java/com/example/myapplication/engine/BertWordPieceTokenizer.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/BertWordPieceTokenizer.kt)
  - Model File: `scam_classifier_int8.onnx` (238,733,951 bytes / ~227.67 MB)
  - Vocab: `vocab.txt` (3,162,999 bytes / ~3.02 MB, 197,285 tokens)
- **Current State in Repository:**
  - **FULLY WIRED & ACTIVE**: The fine-tuned MuRIL BERT model is the active on-device binary scam classifier used in Stage 6 of [`ScamDetectionEngine.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/ScamDetectionEngine.kt).
  - Runtime: Microsoft ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android:1.19.2`).
  - Managed by [`ModelDownloadManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt) with post-install downloading from GitHub Releases (`BuildnByte/HoneyPot_App`), eliminating APK bloat.
- **Model Specifications (Verified via ML Handoff Report):**
  - Base Architecture: `google/muril-base-cased` (Multilingual Representations for Indian Languages).
  - Checkpoint: `checkpoint-2730` (Validation F1: 0.997575).
  - Task: Binary SMS scam classification (0 = HAM/SAFE, 1 = SCAM/SPAM).
  - Max Sequence Length: 128 tokens.
  - Tokenizer: Native Kotlin WordPiece tokenizer matching `google/muril-base-cased` (`do_lower_case: false`, `cls_token: "[CLS]"`, `sep_token: "[SEP]"`).
  - Note on `tokenizer_config.json`: **Not needed or loaded on Android**. All tokenizer rules (casing, max sequence length of 128, and token IDs `[PAD]=0`, `[UNK]=1`, `[CLS]=2`, `[SEP]=3`, `[MASK]=4`) are hardcoded directly in [`BertWordPieceTokenizer.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/BertWordPieceTokenizer.kt). Only `vocab.txt` is loaded at runtime.
  - Quantization Reality: **Dynamic INT8 Quantized** (reduced by 74.88% from the original **906.41 MB FP32** ONNX export).
    * *Why is it ~228 MB?* MuRIL covers 17 Indian languages + English, giving it an exceptionally large vocabulary table (197,285 tokens). The embedding weight matrix alone is `197,285 × 768 × 4 bytes ≈ 606 MB` in FP32, accounting for the majority of the quantized model's disk footprint.
  - Test Metrics (INT8 ONNX on 2,439-message held-out test set):
    * Accuracy: **99.631%**
    * Precision: **99.037%**
    * Scam Recall: **99.516%**
    * F1 Score: **99.276%**
    * FP32 vs. INT8 Agreement: **99.754%** (only 6 differing predictions across 2,439 test samples).
    * CPU Inference Latency: ~38.7 ms/message (median 39.09 ms).

#### B. Legacy Shim: Gemma 3 1B LLM Classifier (`ScamLlmClassifier.kt`)
- **File Path:** [`app/src/main/java/com/example/myapplication/llm/ScamLlmClassifier.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/ScamLlmClassifier.kt)
- **Current State:**
  - **DEPRECATED & REMOVED:** The on-device Gemma 3 1B model (`gemma3-1B-it-int4.task`, ~554 MB) was removed from the active pipeline to eliminate high memory consumption (~1.5 GB RAM) and multi-second inference latency.
  - `ScamLlmClassifier.kt` is retained as a safe no-op shim to avoid breaking any stale call-sites.
  - **No Android AI Core:** The application does **NOT** use Android AI Core (AICore) as a backend or runtime for on-device Gemini/Gemma models. All on-device inference runs strictly through ONNX Runtime Mobile (`OnnxScamClassifier`) and MediaPipe Tasks (`TextEmbedder`).
  - On-device classification is handled exclusively by **MuRIL BERT ONNX** ([`OnnxScamClassifier.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/OnnxScamClassifier.kt)).
  - Cloud reasoning and deep entity extraction are handled by Google Gemini (`gemini-3.5-flash-lite`) via [`CloudReasoningService.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/CloudReasoningService.kt).
  - Honeypot reply suggestions are handled by [`CloudReplyGenerator.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/CloudReplyGenerator.kt) (`gemini-3.5-flash-lite`) with deterministic local fallback in [`ReplyProgressionEngine.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/ReplyProgressionEngine.kt).

---

### 2.2. Evidence Extraction

- **File Paths:**
  - Structured Regex: [`app/src/main/java/com/example/myapplication/preprocessor/SmsPreprocessor.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/preprocessor/SmsPreprocessor.kt)
  - On-Device Rule-Based NER: [`app/src/main/java/com/example/myapplication/engine/ScamEntityExtractor.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/ScamEntityExtractor.kt)
  - Supplemental Cloud NER: [`app/src/main/java/com/example/myapplication/llm/CloudReasoningService.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/CloudReasoningService.kt)
  - Central Orchestrator: [`app/src/main/java/com/example/myapplication/engine/ScamDetectionEngine.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/ScamDetectionEngine.kt)
- **Entry Points:** `SmsPreprocessor.process(sms: IncomingSms)`, `ScamEntityExtractor.extract(smsBody: String)`, `ScamDetectionEngine.processMessage()`, `ScamDetectionEngine.processConfirmedScamFollowUp()`
- **What It Does Today (Hybrid Regex + Rule-based NER + Supplemental Cloud NER):**
  - **Deterministic Regex Extraction (`SmsPreprocessor.kt`):**
    - `URL_REGEX`: `https?://[^\s]+|www\.[^\s]+`
    - `UPI_REGEX`: `[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}`
    - `PHONE_REGEX`: `(\+91[\-\s]?)?[6-9]\d{9}`
    - `ACCOUNT_REGEX`: `(?<![+\d])\d{9,18}(?!\d)` (executed *after* replacing detected phone numbers with `[PHONE]` to avoid capturing 10-digit phone numbers as bank accounts)
    - `AMOUNT_REGEX`: `(₹|rs\.?|inr)\s*[\d,]+(\.\d{2})?` (case-insensitive)
    - `OTP_NUMBER_REGEX`: `\b\d{4,8}\b` (used for text normalisation)
  - **On-Device Rule-Based NER (`ScamEntityExtractor.kt`):**
    - Identifies unstructured named entities locally without requiring an LLM:
      * Person Names via honorific prefixes (`Mr.`, `Mrs.`, `Dr.`, `Shri`, `Inspector`, etc.)
      * Authority Roles (`Branch Manager`, `CBI Officer`, `Cyber Crime Cell`, `Support Executive`)
      * Government & Banking Organizations (`RBI`, `SBI`, `HDFC`, `Electricity Board`, `Income Tax Department`)
      * Partial payment card numbers (`\b(?:\d{4}[\s\-]){3}\d{4}\b`)
      * Bank IFSC codes (`\b[A-Z]{4}0[A-Z0-9]{6}\b`)
      * Transaction reference numbers (`Txn ID`, `Ref No`, etc.)
      * Email addresses and urgency date/time expressions
  - **Supplemental Cloud NER (`CloudReasoningService.kt` — First Message Only):**
    - When cloud reasoning is enabled, Google Gemini (`gemini-3.5-flash-lite`) reviews the first detected scam message and extracts any subtle named entities that regex and rule-based patterns missed.
    - If offline or cloud reasoning is disabled, a local rule-based template provides the rationale.
  - **Deduplication & Storage (`ScamDetectionEngine.kt`):**
    - Merges regex entities, on-device rule-based NER entities, and cloud entities into a unified `List<ExtractedEntity>` deduplicated by `type:value.lowercase()`.
    - Persists them into `scam_evidence` table using `insertEvidenceBatch()` with `OnConflictStrategy.IGNORE` under a database-level `UNIQUE(sessionId, entityType, entityValue)` index.
  - **Follow-up Fast-Path (`processConfirmedScamFollowUp`):**
    - When a session is already confirmed scam, follow-up attacker messages skip classification and cloud calls entirely; regex entities (`PHONE`, `UPI_ID`, `ACCOUNT`, `URL`, `AMOUNT`) revealed during ongoing engagement are extracted directly to the evidence vault.
- **Inputs:** `IncomingSms` (sender: `String`, body: `String`, timestamp: `Long`, subscriptionId: `Int`, serviceCenterAddress: `String`).
- **Outputs:** `List<ExtractedEntity>` (type: `String`, value: `String`) and database `ScamEvidence` entities.
- **Dependencies:** Standard Kotlin stdlib regex, OkHttp (Gemini API for first-message supplementary NER), and Android Room DAO.

---

### 2.3. Google Safe Browsing API Integration

- **File Path:** [`app/src/main/java/com/example/myapplication/verification/FactCheckService.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/verification/FactCheckService.kt)
- **Main Entry Point:** `FactCheckService.checkDomainReputation(domain: String): FactCheckResult`
- **What It Does Today:**
  - Reads `BuildConfig.SAFE_BROWSING_API_KEY` (injected from `local.properties` at build time).
  - If the key is blank, immediately returns `FactCheckResult("DOMAIN", domain, "UNVERIFIED", 0.0f, "safe_browsing_key_not_configured")` without attempting network calls.
  - Performs a synchronous HTTP POST using `java.net.HttpURLConnection` inside Kotlin `withContext(Dispatchers.IO)`.
  - Target URL: `https://safebrowsing.googleapis.com/v4/threatMatches:find?key=$apiKey`
  - Request Payload:
    ```json
    {
      "client": { "clientId": "honeypot-app", "clientVersion": "1.0" },
      "threatInfo": {
        "threatTypes": ["MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE"],
        "platformTypes": ["ANY_PLATFORM"],
        "threatEntryTypes": ["URL"],
        "threatEntries": [{ "url": "<domain_or_url>" }]
      }
    }
    ```
  - Response Handling: Checks if `matches` array exists and length > 0. If malicious, returns verdict `MALICIOUS` (confidence 0.9f); if clean, returns verdict `SAFE` (confidence 0.7f).
  - Orchestration in `verifyEntities()`:
    - Runs domain checks concurrently across all detected URLs using Kotlin Coroutines `coroutineScope { urls.map { async { checkDomainReputation(it) } }.awaitAll() }`.
- **Config & Operational Parameters:**
  - Connection Timeout: `5000 ms` (5 seconds).
  - Read Timeout: `5000 ms` (5 seconds).
  - Rate Limiting: **None implemented**. No token bucket, request queue, exponential backoff, or cache exists.
  - Error Handling:
    - HTTP status != 200: Logs error and returns `FactCheckResult("DOMAIN", domain, "UNVERIFIED", 0.0f, "safe_browsing_api_error")`.
    - Catch-all `Exception`: Returns `FactCheckResult("DOMAIN", domain, "UNVERIFIED", 0.0f, "safe_browsing_network_error")`.
  - **CRITICAL NOTE:** In `c:\FYP\local.properties`, `SAFE_BROWSING_API_KEY` is currently **absent**, so in this development build, Safe Browsing always returns `UNVERIFIED`.

---

### 2.4. Orchestration & Decision Layer

#### A. Central Detection Engine (`ScamDetectionEngine.kt`)
- **File Path:** [`app/src/main/java/com/example/myapplication/engine/ScamDetectionEngine.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/ScamDetectionEngine.kt)
- **Main Entry Point:** `ScamDetectionEngine.processMessage(sms: IncomingSms, history: List<String>, explicitSessionId: String?): DetectionResult`
- **Execution Workflow:**
  1. **Pre-filter (`SmsPreFilter`):** Checks sender against DLT headers (`^[A-Z]{2}-[A-Z0-9]{6}(-[A-Z]+)?$`) and safe cache. If legitimate institutional transaction, returns early with `FilterResult.IGNORE`.
  2. **Preprocessing (`SmsPreprocessor`):** Normalises text, extracts regex entities, builds 16-element float feature vector.
  3. **Trigger Evaluation (`TriggerEngine`):** Scans for authority impersonation (RBI, CBI, police), account threats (KYC expired, blocked), credential requests (OTP, CVV, PIN), financial lures, and urgency pressure. Determines priority (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`). If no triggers fire and vector search is empty, pipeline halts early.
  4. **Vector Search (`VectorSearchEngine`):** Uses MediaPipe `universal_sentence_encoder_lite.tflite` to generate 256-dim embedding of full conversation; computes cosine similarity against stored `knowledge_base` entries; filters candidates with similarity >= 0.45f, returns top 3.
  5. **Fact Check Verification (`FactCheckService`):** Simultaneously queries local indicator KB, prior extracted evidence, and Google Safe Browsing API.
  6. **On-Device MuRIL BERT Classification (`OnnxScamClassifier`):**
     - Runs INT8 ONNX inference (`scam_classifier_int8.onnx`). If safe (`!bertResult.isScam`), returns early with clean verdict.
     - *Graceful Fallback:* If model files are not yet downloaded via `ModelDownloadManager`, high/critical triggers fallback to heuristic score (`CRITICAL`=0.95, `HIGH`=0.85, else=0.65).
  7. **Confidence Blending (Stage 6b):**
     $$\text{Blended Confidence} = \begin{cases} P(\text{scam}), & \text{if } P(\text{scam}) \ge 0.90 \\ (P(\text{scam}) \times 0.75) + (\text{Trigger Score} \times 0.25), & \text{otherwise} \end{cases}$$
     Where Trigger Score is: `CRITICAL`=1.0, `HIGH`=0.8, `MEDIUM`=0.5, `LOW`=0.2.
  8. **Scam Subtype Resolution (Stage 6c):** Resolves scam category (e.g., `KYC Phishing`, `Lottery Scam`, `Electricity Bill`) via `ScamTypeResolver.resolve(triggerResult, kbResults)`.
  9. **On-Device Rule-Based NER (Stage 6d):** Extracts names, roles, organizations, card patterns, IFSC codes, and transaction refs via `ScamEntityExtractor.extract(sms.body)` and merges with `SmsPreprocessor` regex entities.
  10. **Fact Checking (`FactCheckService`):** Verifies domain reputation via Safe Browsing and local threat DB.
  11. **Cloud Reasoning & Supplemental NER (Stage 8 — First Message Only):**
      - Calls Google Gemini (`gemini-3.5-flash-lite`) via `CloudReasoningService.analyzeFirstMessage()` for a concise 2-3 sentence explanation and any missed entities.
      - Falls back to local deterministic templates if offline or API key is absent.
  12. **Evidence Logging (Stage 9):** Inserts all extracted entities to `scam_evidence` Room table.
  13. **Honeypot Reply Suggestion (Stage 10):** Calls `CloudReplyGenerator.generateReply()` (Google Gemini with deterministic `ReplyProgressionEngine` fallback).

#### B. Heavy Engine Lifecycle Singleton (`ScamDetectionEngineManager.kt`)
- **File Path:** [`app/src/main/java/com/example/myapplication/engine/ScamDetectionEngineManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/ScamDetectionEngineManager.kt)
- **What It Does:** Uses a Kotlin `Mutex` to guarantee only one singleton instance of `ScamDetectionEngine` (managing ONNX Runtime session, WordPiece tokenizer, and MediaPipe USE embedder) is instantiated across the app.

#### C. Cross-App Capture Orchestration (`ManualAnalysisRepository.kt`)
- **File Path:** [`app/src/main/java/com/example/myapplication/manualcapture/ManualAnalysisRepository.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/manualcapture/ManualAnalysisRepository.kt)
- **What It Does:** Bridges OCR text from screenshots into `ScamDetectionEngine`. Synthesises an `IncomingSms(sender = "MANUAL_CAPTURE", body = selectedText)` and a `SimulationSession(name = "[CAPTURE] <app> <timestamp>")`. Runs `processMessage()`, persists `AiAnalysisLog` and `ManualCaptureAnalysis`, updates capture status to `COMPLETE`.

---

### 2.5. Gemini API Usage

- **File Paths:**
  - Response Generation: [`app/src/main/java/com/example/myapplication/llm/CloudReplyGenerator.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/CloudReplyGenerator.kt)
  - First-Message Reasoning & Supplemental NER: [`app/src/main/java/com/example/myapplication/llm/CloudReasoningService.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/CloudReasoningService.kt)
- **Scope Confirmation:**
  - **No Primary Scam Classification:** The Gemini API does **NOT** classify incoming messages as HAM vs. SCAM. Primary binary scam classification is handled strictly on-device by MuRIL BERT ONNX (`OnnxScamClassifier.kt`).
  - **No Android AI Core:** All cloud inference calls Google's REST endpoint via OkHttp. Android AI Core is **not used**.
  - **Specific Use Cases:**
    1. *CloudReasoningService:* First detected scam message per session only; generates 2-3 sentence human-readable rationale and extracts supplementary named entities.
    2. *CloudReplyGenerator:* Generates context-aware honeypot response suggestions to bait the attacker.
- **Operational Protocol:**
  - Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$apiKey`
  - API Key Source: `BuildConfig.GEMINI_API_KEY` (injected from `local.properties`).
  - Consent Gate: Calls are gated by `ConsentPreferenceManager.kt`—stored per-contact in `SharedPreferences`. The app prompts the user: *"Let AI craft smarter replies? ... What gets sent: message text only. What stays local: your number, all detection & evidence"*.
  - Persona & Prompt:
    - System instruction defines "Alex": a curious, unsuspecting persona.
    - Instructions: Ask a clarifying question that naturally reveals the other party's full name, organization, phone number, bank account, UPI ID, or address; do not mention money transfers or give personal details; do not repeat prior questions.
    - Configuration: `temperature: 0.8`, `maxOutputTokens: 512`, `thinkingConfig: { thinkingLevel: "low" }`.
  - Fallback Mechanism: If `GEMINI_API_KEY` is blank (or set to placeholder `"YOUR_GEMINI_API_KEY_HERE"`), or if network fails, or if safety blocks (`finishReason == "SAFETY"`), it seamlessly falls back to `ReplyProgressionEngine.nextReply()`.

#### Deterministic Fallback (`ReplyProgressionEngine.kt`)
- **File Path:** [`app/src/main/java/com/example/myapplication/llm/ReplyProgressionEngine.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/llm/ReplyProgressionEngine.kt)
- **What It Does:** Solves the problem where Gemma 3 1B fails at context tracking across multiple turns. Parses conversation history to track what entities the attacker has **revealed** (`hasName`, `hasPhone`, `hasUrl`, `hasAmount`, `hasUpi`, `hasAccount`, `hasLocation`, `hasId`) and what topics the victim **already asked**. Matches against curated question banks for 9 scam types (Prize/Lottery, Phishing, KYC, Electricity, Job, Investment, Digital Arrest, Parcel, Generic) and deterministically selects the next unasked question variant.

---

## 3. Data & Models

### 3.1. Datasets in Repository
- **Raw Data Files on Disk:** There are **no** `.csv`, `.tsv`, `.jsonl`, or `.parquet` dataset files present in this repository tree.
- **Embedded / Seed Knowledge Base Data (`ScamDetectionEngine.kt`):**
  The database self-seeds if `knowledge_base` count is 0 with three hardcoded entries:
  1. *KYC Phishing:* "Dear customer your KYC is expired. Account will be blocked. Update now: http://fake-bank.com" (Severity 3)
  2. *Lottery Scam:* "Congratulations! You won 1 Crore in KBC. Call 9988776655 to claim your prize." (Severity 4)
  3. *Electricity Bill Scam:* "Dear consumer, your electricity will be disconnected tonight at 9:30 PM because your previous month bill was not updated. Please contact our officer 8877665544. Thank you." (Severity 3)
- **Documented Training Dataset (from `upd/MODEL_CARD.md`):**
  - Fine-tuning dataset: 29,104 balanced training examples; 2,439 validation examples; 2,439 held-out test examples.
  - Languages: Multilingual / Indian English & Hinglish (derived from base `google/muril-base-cased` domain).
  - Synthetic Augmentation: A test experiment trained on 1,000 synthetic scam messages across 8 Indian scam categories (CBI/Police, Electricity, IT refund, Job offer, KYC, Lottery, OTP, RBI). It was discarded because it produced 1 false positive compared to 0 for the baseline.

### 3.2. Model Artifacts & Delivery

| File Path / Source | Description | Actual Size | Delivery Mechanism |
|---|---|---|---|
| `app/src/main/assets/muril/scam_classifier_int8.onnx` (or GitHub Releases) | Fine-tuned MuRIL base binary scam classification model in ONNX format (INT8 dynamic quantized). | **238,733,951 bytes** (~227.67 MB) | Assets fallback or downloaded via `ModelDownloadManager` |
| `app/src/main/assets/muril/vocab.txt` (or GitHub Releases) | Vocabulary file for MuRIL BertWordPieceTokenizer (197,285 tokens). | **3,162,999 bytes** (~3.02 MB) | Assets fallback or downloaded via `ModelDownloadManager` |
| `app/src/main/assets/universal_sentence_encoder_lite.tflite` (or GitHub Releases) | MediaPipe Text Embedder model for semantic vector search in Knowledge Base. | **6,120,274 bytes** (~5.84 MB) | Assets fallback or downloaded via `ModelDownloadManager` |
| `gemma3-1B-it-int4.task` | MediaPipe LiteRT-LM 4-bit Gemma 3 1B instruction-tuned model. | ~554 MB | **DEPRECATED & REMOVED** |
| `c:\FYP\$123_qwen_abc.task` | Qwen LLM task file in project root (experimental LiteRT conversion test). | **1,598,556,720 bytes** (~1.49 GB) | Untracked in root |

### 3.3. Training, Quantization, and Benchmarking Scripts
- **`muril_quantization_and_benchmark.ipynb`**: Interactive Google Colab notebook in repository root for reproducing dynamic INT8 quantization, running CPU latency benchmarks (P50, P95), calculating logit drift, and validating accuracy on Indian scam/ham test messages.
- **`MODEL_UPDATE_GUIDE.md`**: Comprehensive developer guide in repository root detailing precautions, tensor contracts, vocabulary synchronization rules, and step-by-step instructions for publishing new models via GitHub Releases.
- **`Scam Detection Model- handoff report (1).docx`**: Detailed technical report compiled by the ML team member documenting fine-tuning of `checkpoint-2730`, FP32 vs. INT8 validation, synthetic data balancing, and performance benchmarks.

---

## 4. Build & Deployment State

### 4.1. Android Integration Status: In-App vs. Standalone / External

```text
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                   IN THE ANDROID APP                                     │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ ✅ Real SMS Interception: SmsReceiver + SmsProcessingService (Foreground Service)         │
│ ✅ On-Device MuRIL BERT ONNX Scam Detection: OnnxScamClassifier + BertWordPieceTokenizer │
│ ✅ Post-Install Model Delivery System: ModelDownloadManager (GitHub Releases downloader) │
│ ✅ Settings UI Model Manager: Download progress bar, badge indicators, storage cleanup  │
│ ✅ MediaPipe USE Lite Embedding & Vector Search: ScamTextEmbedder + VectorSearchEngine   │
│ ✅ Pre-filtering & Heuristic Behavioral Engine: SmsPreFilter + TriggerEngine             │
│ ✅ Google Safe Browsing API Client: FactCheckService                                     │
│ ✅ Cloud Honeypot Reply Generation: CloudReplyGenerator (Google Gemini 3.5 Flash)        │
│ ✅ Cloud Reasoning & Deep NER: CloudReasoningService (Google Gemini 3.5 Flash)           │
│ ✅ Local Deterministic Honeypot Progression: ReplyProgressionEngine                      │
│ ✅ Cross-App Silent Screen Capture: HoneypotAccessibilityService (Android 11+ buffer)    │
│ ✅ Fallback Screen Capture: OneShotCaptureService (MediaProjection API 29+)              │
│ ✅ Floating UI & Interactive Overlay Lens: InPlaceLensController, BlockOverlayView,       │
│    OverlayController, ScanOverlayService, ScanQuickSettingsTileService                   │
│ ✅ ML Kit Bundled Latin OCR: ScreenTextRecognizer + MessageSideClassifier                │
│ ✅ Live Interactive Honeypot Conversation UI: HoneypotConversationActivity (with real    │
│    outgoing SMS dispatch via SmsManager)                                                 │
│ ✅ Testing Sandbox UI: SimulationActivity, SessionListActivity, CreateSessionBottomSheet │
│ ✅ Evidence Vault & Cross-Session Entity Tracking: EvidenceActivity, EvidenceDetailAct. │
│ ✅ PDF Crime Incident Report Export: PdfReportGenerator + FileProvider                   │
│ ✅ Room Database v13: KnowledgeBaseDatabase (8 tables with forward migrations 6->13)     │
└──────────────────────────────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                REMOVED / EXTERNAL ASSETS                                 │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│ 🗑️ Gemma 3 1B LLM (gemma3-1B-it-int4.task): Removed (554MB size / 1.5GB RAM eliminated)   │
│ ❌ Qwen Task File ($123_qwen_abc.task): 1.5GB model file sits loose in project root      │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2. Build System & Dependency Manifests

#### Top-level Build Files
- **Gradle Version:** Gradle Wrapper using Gradle 8.x / 9.x daemon.
- **Android Gradle Plugin (AGP):** `9.3.0`
- **Kotlin:** `2.0.21`
- **KSP:** `2.0.21-1.0.28`
- **Manifest / SDK Targets:** `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Java 17 compatibility.
- **NDK ABI Filters:** `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
- **Resource Packaging (`noCompress`):** `["tflite", "task", "litertlm", "bin"]`.
- **Jetifier Configuration:** `android.enableJetifier=false` in `gradle.properties` (disabled as 0 dependencies require Jetifier, accelerating build times).

#### Key Dependencies (`app/build.gradle.kts` & `gradle/libs.versions.toml`)
- `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` (Microsoft ONNX Runtime Mobile for MuRIL classification)
- `androidx.room:room-runtime:2.7.1` & `androidx.room:room-ktx:2.7.1` (compiled via KSP)
- `com.google.mediapipe:tasks-text:1.0.0` (MediaPipe Text Embedder for USE Lite)
- `com.google.mediapipe:tasks-genai` — **REMOVED** (eliminated ~174 MB from universal debug APK and ~39 MB from installed arm64 device footprint)
- `com.google.mlkit:text-recognition:16.0.1` (Bundled on-device Latin OCR recognizer)
- `com.squareup.okhttp3:okhttp:4.12.0` (HTTP client for Gemini API & Safe Browsing)
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1`
- `androidx.lifecycle:lifecycle-service:2.8.4`
- `com.google.android.material:material:1.12.0`
- `androidx.appcompat:appcompat:1.7.0`
- `androidx.core:core-ktx:1.13.1`

---

## 5. Known Gaps / Explicitly Deferred Work

### 5.1. Code Comments & Explicit Mentions of Deferred Items
- **`data_extraction_rules.xml` (Line 8):**
  `<!-- TODO: Use <include> and <exclude> to control what is backed up. -->`
- **Commit `13a243f` Message:**
  `"Pending: notification based analysis, addition of 'add or remove from quick settings' toggle"` *(Note: 'add or remove from quick settings' was subsequently implemented in commit `7b1f0f6` in `CrossAppSettingsActivity.kt`, but notification-based analysis remains pending).*
- **`fix_plan.txt`:**
  Contains: `"Plan to fix OneShotCaptureService"` (OneShotCaptureService uses MediaProjection; accessibility service was introduced as a silent workaround).

### 5.2. Scam Subtype Classification Status
- **MuRIL Model:** Strictly binary classification (0 = HAM, 1 = SCAM). As stated in `MODEL_CARD.md`: *"This model performs binary scam detection only. It does not currently classify the specific scam subtype."*
- **In-App Engine:** Subtype classification is deterministically resolved on-device via `ScamTypeResolver.resolve(triggerResult, kbResults)`. It combines fired keyword trigger groups with semantic vector similarity matches from `knowledge_base` entries to classify the scam into categories (e.g. `KYC Phishing`, `Electricity Bill`, `Lottery Scam`, `Job Fraud`, `Digital Arrest`, `Investment Scam`). A formal multi-class deep neural subtype classifier is not trained, but `ScamTypeResolver` provides robust deterministic category mapping.

### 5.3. Evidence Extraction Completeness
- **Regex Extraction:** Fully functional for 5 core entity types (`PHONE`, `UPI_ID`, `ACCOUNT`, `URL`, `AMOUNT`) via `SmsPreprocessor.kt`.
- **On-Device Rule-Based NER:** Unstructured entities (`Person Names`, `Authority Roles`, `Organizations`, `Card Numbers`, `Bank IFSC Codes`, `Txn References`, `Emails`, `Dates/Times`) are extracted locally via `ScamEntityExtractor.kt` using curated pattern matching and honorific dictionaries.
- **Supplemental Cloud NER:** On the first detected scam message of a session, `CloudReasoningService.kt` invokes Google Gemini (`gemini-3.5-flash-lite`) to extract any subtle named entities missed by local rules.
- **Follow-Up Fast-Path:** When an attacker sends subsequent messages in an active confirmed scam session, `processConfirmedScamFollowUp()` extracts new regex entities directly to the database without invoking BERT or Gemini, optimizing battery and latency.

### 5.4. MuRIL Latency and Size Constraints (Resolved via Post-Install Delivery)
- **Quantization Status:** The MuRIL ONNX model (`scam_classifier_int8.onnx`, ~227.67 MB) is **already dynamically INT8 quantized** from its original 906.41 MB FP32 export (74.88% compression).
- **Why ~228 MB?** MuRIL covers 17 Indian languages and English, necessitating a 197,285-token vocabulary table. Its embedding matrix alone accounts for the bulk of the model footprint. (The teammate's handoff report identifies MobileBERT as an alternative if strict <35 MB footprint is required).
- **APK Bloat Resolution:** To eliminate packaging bloat, [`ModelDownloadManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt) fetches the models post-install from GitHub Releases (`StoneCollector/honeypot-models`, release tag `v1.0-models`), keeping the base APK at ~15 MB and preventing the duplicate internal storage extraction penalty.
- **Physical Device vs. Emulator Verification:** Model downloading and on-device initialization are verified and operational on physical Android hardware. On Android emulators, QEMU NAT/DNS can stall when opening long-lived TLS connections to Microsoft Azure/GitHub CDN endpoints (`release-assets.githubusercontent.com`), which is an emulator-specific network constraint.
- **Interactive Cancellation & Progress UX:** When a download begins, the "Download Models" button dynamically morphs into an enabled "Cancel Download" button. Tapping it immediately aborts active OkHttp sockets, terminates coroutines, cleans up `.tmp` files, and reverts the UI state.
- **Gemma 3 1B Elimination:** The 554.7 MB Gemma model was completely removed, eliminating its ~1.5 GB RAM consumption and slow sequential mutex queue. MuRIL ONNX inference runs in ~38.7 ms on CPU.

### 5.5. Deferred Manual Capture Features (vs. `MANUAL_CAPTURE_FEATURE_PLAN.md`)
- **PDF Report Screenshot Embedding:** Planned in specification (Section: History/report integration: *"Update PdfReportGenerator to embed a scaled image (preserving aspect ratio)... print the SHA-256 hash"*), but `PdfReportGenerator.kt` currently only prints textual identifiers and transcripts. It does not embed the image or the SHA-256 digest.
- **OCR Scripts:** Only Latin alphabet ML Kit is bundled (`com.google.mlkit:text-recognition:16.0.1`). Devanagari script recognition for Hindi/regional chat screenshots was explicitly deferred.
- **Discord Support:** Excluded by design because Discord's left/right layout breaks message side heuristics.

---

## 6. Recent Changes (Git History)

The 15 most recent commits on the active `stc` branch:

| Commit | Date | Author | Commit Message | Key Files Touched |
|---|---|---|---|---|
| **`7b1f0f6`** | 2026-09-09 | Stone Collector | added button toggle for quick settings | `CrossAppSettingsActivity.kt`, `activity_crossapp_settings.xml` |
| **`13a243f`** | 2026-09-05 | Stone Collector | Feat: majority work complete. Pending: notification based analysis, addition of 'add or remove from quick settings' toggle | `InPlaceLensController.kt`, `HoneypotAccessibilityService.kt`, `OverlayController.kt`, `ScanOverlayService.kt`, `overlay_in_place_lens.xml`, `overlay_dismiss_target.xml` |
| **`51d5597`** | 2026-09-05 | Stone Collector | Fine Tuning | `CaptureReviewActivity.kt`, `MessageSideClassifier.kt`, `ScreenTextRecognizer.kt`, `bottom_sheet_selected_text.xml` |
| **`fdda107`** | 2026-09-05 | Stone Collector | Feat: Interactive message selection implemented | `BlockOverlayView.kt`, `MessageSideClassifierTest.kt` |
| **`573c004`** | 2026-09-05 | Stone Collector | Overlay mechanism improved - instant screenshot (with accessibiltiy permissions) | `HoneypotAccessibilityService.kt`, `accessibility_service_config.xml`, `CaptureConsentActivity.kt`, `CrossAppSettingsActivity.kt` |
| **`4e62912`** | 2026-09-05 | Stone Collector | Added overlay mechanism for reading texts over other apps (Performs poorly in this commit) | `MANUAL_CAPTURE_FEATURE_PLAN.md`, `OneShotCaptureService.kt`, `CaptureConsentActivity.kt`, `CaptureReviewActivity.kt`, `ManualAnalysisActivity.kt`, `ManualAnalysisRepository.kt`, `ManualCapturePrefs.kt`, `OverlayController.kt`, `ScanOverlayService.kt`, `ScanQuickSettingsTileService.kt` |
| **`9990fd3`** | 2026-08-31 | Stone Collector | UI Overhaul | `MainActivity.kt`, `HoneypotConversationActivity.kt`, `SimulationActivity.kt`, `EvidenceActivity.kt`, `SessionListActivity.kt`, themes/colors XML |
| **`34e0b11`** | 2026-08-31 | “BuildnByte” | Implement medium impact usability features in Evidence Vault | `PdfReportGenerator.kt`, `EvidenceDetailActivity.kt`, `TimelineAdapter.kt`, `SimulationSession.kt`, `file_paths.xml` |
| **`7ee366d`** | 2026-08-30 | “BuildnByte” | feat: complete audit fixes including safe browsing, reply progression, and confidence blending | `ScamDetectionEngine.kt`, `FactCheckService.kt`, `ReplyProgressionEngine.kt`, `CloudReplyGenerator.kt`, `HoneypotConversationActivity.kt`, `SmsProcessingService.kt`, `MainActivity.kt` |
| **`e3f6157`** | 2026-08-14 | “BuildnByte” | Added feature to create multiple sessions with name and id's | `SimulationSession.kt`, `SessionListActivity.kt`, `SessionAdapter.kt`, `CreateSessionBottomSheet.kt`, `EvidenceDetailActivity.kt`, `KnowledgeBaseDatabase.kt` (Room Migration 10_11) |
| **`54c55cb`** | 2026-08-14 | “BuildnByte” | Integrated gemini-flask model using google api for better suggested replys | `CloudReplyGenerator.kt`, `ConsentPreferenceManager.kt`, `ReplyProgressionEngine.kt`, `SimulationActivity.kt`, `app/build.gradle.kts` |
| **`8a12e03`** | 2026-08-13 | “BuildnByte” | Fixed errors related to model output and error vault | `ScamDetectionEngine.kt`, `ScamLlmClassifier.kt`, `SmsProcessingService.kt`, `TextEmbedder.kt`, `KnowledgeBaseDatabase.kt` (Room Migration 8_9) |
| **`f92a6da`** | 2026-08-11 | Stone Collector | Classifier and extractor finished | `ScamDetectionEngine.kt`, `ScamDetectionEngineManager.kt`, `FactCheckService.kt`, `EvidenceActivity.kt`, `EvidenceAdapter.kt`, `IndicatorEntry.kt` |
| **`ca6443f`** | 2026-08-10 | Stone Collector | Stupid changes | `ChatAdapter.kt`, `SimulationActivity.kt`, `ScamLlmClassifier.kt` |
| **`1242ffe`** | 2026-08-10 | Stone Collector | Test for model | Kotlin compiler session test |

---

## 7. Open Questions for the Next Contributor

1. **Gemma vs. MuRIL Classifier Divergence [RESOLVED]:**
   - *Resolution:* MuRIL BERT ONNX (`scam_classifier_int8.onnx`) is now fully wired via [`OnnxScamClassifier.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/OnnxScamClassifier.kt) and [`BertWordPieceTokenizer.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/BertWordPieceTokenizer.kt) using Microsoft ONNX Runtime Mobile (`1.19.2`). The 554 MB Gemma 3 1B model was completely removed from the pipeline (leaving `ScamLlmClassifier.kt` as a no-op shim). Post-install model delivery was implemented via [`ModelDownloadManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt) using GitHub Releases (`StoneCollector/honeypot-models`), keeping the APK download footprint under ~15 MB.
2. **Missing API Keys in Local Environment:**
   - In `local.properties`, `SAFE_BROWSING_API_KEY` and `GEMINI_API_KEY` are not set.
   - Consequently, `FactCheckService` always returns `UNVERIFIED` (source: `safe_browsing_key_not_configured`), and `CloudReplyGenerator` always falls back to the deterministic local `ReplyProgressionEngine`. Valid keys must be added to `local.properties` before testing cloud features.
3. **Architectural Conflict: Accessibility Service vs. Privacy Plan:**
   - `MANUAL_CAPTURE_FEATURE_PLAN.md` specifically stated: *"Never use Accessibility to scrape chat UI or infer messages in the background"* and *"Confirm no Accessibility service... is declared by this feature."*
   - In contrast, commits `573c004` and `13a243f` introduced `HoneypotAccessibilityService` to enable silent Google Lens-style screenshots via Android 11's `takeScreenshot` API from the hardware buffer, explicitly bypassing the user-facing MediaProjection permission prompt.
   - *Question:* Is Google Play store compliance a target (Google restricts `AccessibilityService` declarations for non-accessibility tools), or is this an enterprise/research/sideloaded APK where silent capture convenience was intentionally chosen over MediaProjection consent prompts?
4. **Stale Cloud API References in Code Comments:**
   - `app/build.gradle.kts` (line 91) and `AiAnalysisLog.kt` (line 18) contain comments describing OkHttp as: `"used for cloud API (OpenAI GPT-4o-mini) reply generation"`.
   - The actual implementation in `CloudReplyGenerator.kt` and `CloudReasoningService.kt` uses Google Gemini (`gemini-3.5-flash-lite`). The OpenAI comments are stale artifacts from an earlier design.
5. **Orphaned 1.5 GB File in Project Root:**
   - `$123_qwen_abc.task` (1,598,556,720 bytes) is sitting loose in the repository root directory untracked. It should either be moved to an external storage location or gitignored to avoid accidental inclusion.
6. **Unmerged Honeypot Agent Branch:**
   - Branch `remotes/origin/feature/honeypot-agent` contains unmerged classes (`HoneypotAgent.kt`, `AgentAction.kt`, `AgentDecision.kt`, `AgentObjective.kt`, `ConversationState.kt`, `ScamStage.kt`) implementing a formal rule-based honeypot agent. This branch was never merged into `stc` or `main`. It should be evaluated to determine whether its multi-objective state machine should replace or augment `ReplyProgressionEngine.kt`.
