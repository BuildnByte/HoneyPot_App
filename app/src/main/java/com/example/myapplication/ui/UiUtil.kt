package com.example.myapplication.ui

/**
 * Shared UI utility functions used across the Honeypot app.
 */
object UiUtil {

    /**
     * Converts a snake_case trigger name to Title Case for display.
     * e.g. "financial_lure" → "Financial Lure"
     * e.g. "urgency_pressure" → "Urgency Pressure"
     */
    fun formatTriggerName(raw: String): String {
        return raw.split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Converts a raw sourceApp string (as stored in ManualCapture.sourceApp or
     * SimulationSession.serviceCenterAddress) to a clean human-readable label.
     * Never shows "MANUAL_CAPTURE" to the user.
     */
    fun formatSourceApp(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("telegram") -> "Telegram"
            lower.contains("instagram") -> "Instagram"
            lower.contains("messenger") -> "Messenger"
            lower.contains("sms") || lower.contains("message") || lower.contains("mms") -> "Messages"
            lower.contains("signal") -> "Signal"
            lower == "manual_capture" || lower == "manual" -> "Screenshot"
            raw.isBlank() -> "Screenshot"
            else -> raw.trim()
        }
    }

    /**
     * Cleans up a raw session name that may contain internal prefixes like
     * "[CAPTURE]", "[LIVE]", "MANUAL_CAPTURE" etc.
     */
    fun formatSessionName(sessionName: String, attackerPhone: String): String {
        val isCapture = sessionName.contains("[CAPTURE]", ignoreCase = true)
            || sessionName.contains("MANUAL_CAPTURE", ignoreCase = true)

        return when {
            isCapture -> {
                // Extract source app from [CAPTURE] WhatsApp 2025-01-01... style names
                val stripped = sessionName
                    .removePrefix("[CAPTURE]")
                    .replace("MANUAL_CAPTURE", "")
                    .trim()
                // Take the first token which should be the app name
                val firstWord = stripped.split(" ").firstOrNull()?.trim() ?: ""
                if (firstWord.isNotBlank() && !firstWord.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))) {
                    formatSourceApp(firstWord)
                } else {
                    "Screenshot"
                }
            }
            attackerPhone.isNotBlank() && attackerPhone != "MANUAL_CAPTURE" -> {
                val digits = attackerPhone.filter { it.isDigit() }
                if (digits.length >= 10) {
                    val last10 = digits.takeLast(10)
                    "+91 ${last10.substring(0, 5)} ${last10.substring(5)}"
                } else {
                    attackerPhone
                }
            }
            else -> sessionName
                .removePrefix("Honeypot - ")
                .removePrefix("[CAPTURE] ")
                .removePrefix("[LIVE] ")
                .replace("MANUAL_CAPTURE", "Manual Scan")
                .trim()
        }
    }

    /**
     * Returns a source app color resource ID for use in avatar circles, dots etc.
     * Callers should use this with context.getColor(UiUtil.sourceAppColor(app)).
     */
    fun sourceAppColorRes(sourceApp: String): Int {
        return when (formatSourceApp(sourceApp)) {
            "WhatsApp" -> com.example.myapplication.R.color.accent_green
            "Telegram" -> com.example.myapplication.R.color.badge_blue_text
            "Messages" -> com.example.myapplication.R.color.accent_blue
            "Instagram" -> com.example.myapplication.R.color.accent_amber
            "Signal" -> com.example.myapplication.R.color.accent_blue
            else -> com.example.myapplication.R.color.text_secondary
        }
    }

    /**
     * Standardized date formatting according to HoneyPot UI Guidelines:
     * - If today: "Today"
     * - If within current week: Abbreviated day name (e.g. "Thu", "Mon")
     * - If earlier: "dd/MM/yy" (e.g. "12/10/26")
     */
    fun formatDisplayDate(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }

        val isToday = now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
        if (isToday) return "Today"

        val diffMillis = now.timeInMillis - target.timeInMillis
        val diffDays = diffMillis / (1000 * 60 * 60 * 24)
        if (diffDays in 1..6 && now.get(java.util.Calendar.WEEK_OF_YEAR) == target.get(java.util.Calendar.WEEK_OF_YEAR)) {
            return java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }

        return java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

