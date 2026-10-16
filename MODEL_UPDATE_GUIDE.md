# On-Device AI Models — Update Guide & Contributor Cautions

This guide provides instructions and critical cautions for retraining, updating, or replacing the on-device Machine Learning models and vocabulary used by the **Agentic AI Honeypot System**.

---

## 1. Architecture Overview

The application relies on two local ML models managed by [`ModelDownloadManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt) and fetched post-install from **GitHub Releases**:

| Model Asset | Target File Name | Typical Size | Purpose |
| :--- | :--- | :--- | :--- |
| **MuRIL BERT ONNX** | `scam_classifier_int8.onnx` | ~227.67 MB | Binary scam classification (0 = HAM, 1 = SCAM) |
| **MuRIL Vocabulary** | `vocab.txt` | ~3.02 MB | WordPiece tokenizer lookup table (197,285 tokens) |
| **USE Lite Embedder** | `universal_sentence_encoder_lite.tflite` | ~5.84 MB | 256-dim semantic text embedding for Knowledge Base vector search |

Storage path on device: `context.filesDir/models/<filename>`

---

## 2. ⚠️ Critical Cautions (Read Before Updating)

> [!CAUTION]
> ### 1. Vocabulary & ONNX Embedding Table Synchronization
> * **The line numbers in `vocab.txt` directly map to the row indices of the ONNX model's input embedding layer.**
> * If you add, remove, or re-order even a single word in `vocab.txt` without updating the ONNX model weights, **every subsequent token ID will be corrupted**, resulting in catastrophic prediction failure.
> * Always update `vocab.txt` and `scam_classifier_int8.onnx` together from the exact same training checkpoint.

> [!IMPORTANT]
> ### 2. Model Tensor Contract Invariants
> The Kotlin inference engine ([`OnnxScamClassifier.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/OnnxScamClassifier.kt)) expects this exact tensor signature:
> * **Input Tensors (all INT64, shape `[1, 128]`):**
>   1. `input_ids` — Token IDs padded or truncated to 128 tokens
>   2. `attention_mask` — 1 for real tokens, 0 for `[PAD]`
>   3. `token_type_ids` — All zeros for single-sequence classification
> * **Output Tensor (Float32, shape `[1, 2]`):**
>   * `logits` — Index `0` = HAM (benign), Index `1` = SCAM (malicious)
> * **Case Sensitivity:**
>   * MuRIL is a **cased** model (`do_lower_case = false`). Do not change [`BertWordPieceTokenizer.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/engine/BertWordPieceTokenizer.kt) to lowercase unless you retrain the base model on an uncased checkpoint.

> [!WARNING]
> ### 3. Strict Byte Size Verification in `ModelDownloadManager`
> To prevent truncated or corrupted downloads, [`ModelDownloadManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt) validates the exact file byte size.
> * **If you replace a model file, you MUST update its `expectedSizeBytes` constant in `ModelDownloadManager.kt`.**
> * If the constant does not match the new file size, the app will flag the model as incomplete and repeatedly attempt to re-download it.

> [!WARNING]
> ### 4. Never Upload Unquantized FP32 MuRIL Models
> * Because MuRIL supports 17 Indian languages and English, its vocabulary contains **197,285 tokens**.
> * The embedding layer alone is `197,285 × 768 × 4 bytes ≈ 606 MB`.
> * The unquantized FP32 model is **~906 MB**. Uploading an unquantized model will crash mobile devices with Out-Of-Memory (OOM) errors.
> * **Always run dynamic INT8 quantization** before publishing (reducing the file to ~228 MB).

> [!NOTE]
> ### 5. Universal Sentence Encoder Lite & Knowledge Base Cache
> If you update `universal_sentence_encoder_lite.tflite`:
> * The local Room database ([`KnowledgeBaseDatabase`](file:///c:/FYP/app/src/main/java/com/example/myapplication/knowledge/KnowledgeBaseDatabase.kt)) stores precomputed vector embeddings for scam templates.
> * If the new model changes the embedding dimension or vector space, existing database entries will produce invalid similarity scores.
> * You must increment the Room database version in [`KnowledgeBaseDatabase.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/knowledge/KnowledgeBaseDatabase.kt) or trigger `VectorSearchEngine.precomputeEmbeddings()` to re-embed all templates.

---

## 3. Step-by-Step: Retraining & Exporting a New MuRIL Model

### Step 3.1: Export PyTorch Checkpoint to ONNX (Python)

After fine-tuning `google/muril-base-cased` on your scam dataset, export to ONNX:

```python
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

model_dir = "./checkpoint-best"
tokenizer = AutoTokenizer.from_pretrained(model_dir)
model = AutoModelForSequenceClassification.from_pretrained(model_dir)
model.eval()

# Dummy input matching Android inference contract
dummy_text = "Your KYC is expired. Update now."
inputs = tokenizer(
    dummy_text,
    max_length=128,
    padding="max_length",
    truncation=True,
    return_tensors="pt"
)

torch.onnx.export(
    model,
    (inputs["input_ids"], inputs["attention_mask"], inputs["token_type_ids"]),
    "scam_classifier_fp32.onnx",
    input_names=["input_ids", "attention_mask", "token_type_ids"],
    output_names=["logits"],
    dynamic_axes={
        "input_ids": {0: "batch_size"},
        "attention_mask": {0: "batch_size"},
        "token_type_ids": {0: "batch_size"},
        "logits": {0: "batch_size"}
    },
    opset_version=14
)
```

### Step 3.2: Apply Dynamic INT8 Quantization

Use ONNX Runtime to compress the model from ~906 MB down to ~228 MB:

```python
import os
from onnxruntime.quantization import quantize_dynamic, QuantType

quantize_dynamic(
    model_input="scam_classifier_fp32.onnx",
    model_output="scam_classifier_int8.onnx",
    weight_type=QuantType.QInt8,
    per_channel=True,
    reduce_range=False,
    op_types_to_quantize=['MatMul', 'Gemm']
)

print("Quantized file size:", os.path.getsize("scam_classifier_int8.onnx"), "bytes")
```

*(You can also run the full benchmark in the provided [muril_quantization_and_benchmark.ipynb](file:///c:/FYP/muril_quantization_and_benchmark.ipynb) notebook).*

---

## 4. Step-by-Step: Publishing the Update to GitHub

1. Ensure you have the three final files ready:
   * `scam_classifier_int8.onnx`
   * `vocab.txt`
   * `universal_sentence_encoder_lite.tflite`
2. Determine the exact file size in bytes for each file:
   ```powershell
   Get-Item scam_classifier_int8.onnx, vocab.txt, universal_sentence_encoder_lite.tflite | Select-Object Name, Length
   ```
3. Go to GitHub: `https://github.com/BuildnByte/HoneyPot_App/releases`.
4. Click **Draft a new release**.
5. Set a new tag (e.g. `v1.1-models` or `v2.0-models`).
6. Attach the three files as release assets and click **Publish release**.

---

## 5. Step-by-Step: Updating the Android Application

Update [`ModelDownloadManager.kt`](file:///c:/FYP/app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt) to point to the new release and updated byte counts:

```kotlin
// In app/src/main/java/com/example/myapplication/download/ModelDownloadManager.kt

// 1. Update the release tag
var githubOwner: String = "BuildnByte"
var githubRepo: String  = "HoneyPot_App"
var releaseTag: String  = "v1.1-models" // <-- increment tag here

// 2. Update expected byte counts if the file sizes changed
const val MURIL_MODEL_SIZE = 238_733_951L // <-- new size in bytes
const val MURIL_VOCAB_SIZE = 3_162_999L    // <-- new size in bytes
const val USE_MODEL_SIZE   = 6_120_274L    // <-- new size in bytes
```

### Testing the Update in the App:
1. Build and install the app on a test device or emulator.
2. Open **Settings** $\rightarrow$ scroll to **ON-DEVICE AI PROTECTION**.
3. Tap **Delete** to clear the previous cached model.
4. Tap **Download Models** to verify that the app connects to the new GitHub Release, streams with progress, verifies byte integrity, and switches the badge to **Active**.
