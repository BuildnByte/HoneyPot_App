package com.example.myapplication.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.myapplication.model.ScamEvidence
import com.example.myapplication.model.SimulatedMessage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReportGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 50f
    private const val LINE_SPACING = 1.2f

    fun generatePdf(
        context: Context,
        sessionId: String,
        sourceSender: String,
        evidenceWithCounts: List<Pair<ScamEvidence, List<String>>>,
        history: List<SimulatedMessage>
    ): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        
        var currentPage = pdfDocument.startPage(pageInfo)
        var canvas = currentPage.canvas
        var currentY = MARGIN
        var pageNumber = 1

        // Paints
        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headerPaint = TextPaint().apply {
            color = Color.DKGRAY
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val footerPaint = TextPaint().apply {
            color = Color.LTGRAY
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val chatAttackerPaint = TextPaint().apply {
            color = Color.parseColor("#B71C1C") // Red
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val chatVictimPaint = TextPaint().apply {
            color = Color.parseColor("#1565C0") // Blue
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        fun finishPageWithFooter() {
            // Draw page footer before finishing
            val footerY = PAGE_HEIGHT - MARGIN + 10f
            canvas.drawLine(MARGIN, footerY - 5f, PAGE_WIDTH - MARGIN, footerY - 5f, dividerPaint)
            val footerLayout = StaticLayout.Builder.obtain(
                "HoneyPot App - Cybercrime Report  |  Page $pageNumber",
                0, "HoneyPot App - Cybercrime Report  |  Page $pageNumber".length,
                footerPaint, (PAGE_WIDTH - 2 * MARGIN).toInt()
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            canvas.save()
            canvas.translate(MARGIN, footerY)
            footerLayout.draw(canvas)
            canvas.restore()
        }

        // Helper function to draw text and handle pagination
        fun drawTextWrapped(text: String, paint: TextPaint): Float {
            val staticLayout = StaticLayout.Builder.obtain(
                text, 0, text.length, paint, (PAGE_WIDTH - 2 * MARGIN).toInt()
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .build()

            // If this block of text exceeds the page height, create a new page
            if (currentY + staticLayout.height > PAGE_HEIGHT - MARGIN - 30f) {
                finishPageWithFooter()
                pdfDocument.finishPage(currentPage)
                pageNumber++
                val newPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                currentPage = pdfDocument.startPage(newPageInfo)
                canvas = currentPage.canvas
                currentY = MARGIN
            }

            canvas.save()
            canvas.translate(MARGIN, currentY)
            staticLayout.draw(canvas)
            canvas.restore()

            currentY += staticLayout.height + 10f
            return currentY
        }

        fun drawDivider() {
            if (currentY + 20f > PAGE_HEIGHT - MARGIN - 30f) return // Skip if near page end
            canvas.drawLine(MARGIN, currentY + 5f, PAGE_WIDTH - MARGIN, currentY + 5f, dividerPaint)
            currentY += 20f
        }

        // --- DRAW CONTENT ---

        drawTextWrapped("🚨 CYBERCRIME INCIDENT REPORT", titlePaint)
        drawDivider()
        
        drawTextWrapped("Scammer Identifiers (Sender): $sourceSender", bodyPaint)
        if (history.isNotEmpty()) {
            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(history.first().timestamp))
            drawTextWrapped("Incident Date: $dateStr", bodyPaint)
        }
        
        currentY += 10f

        drawTextWrapped("CAPTURED EVIDENCE", headerPaint)
        val grouped = evidenceWithCounts.groupBy { it.first.entityType }
        if (grouped.isEmpty()) {
            drawTextWrapped("No evidence captured.", bodyPaint)
        } else {
            for ((type, items) in grouped) {
                drawTextWrapped("• ${type}s:", chatVictimPaint)
                items.forEach { (evidence, senders) ->
                    drawTextWrapped("    ${evidence.entityValue}", bodyPaint)
                    if (senders.isNotEmpty()) {
                        drawTextWrapped("    (Also seen in: ${senders.joinToString(", ")})", chatAttackerPaint)
                    }
                }
            }
        }

        currentY += 20f
        
        drawTextWrapped("CONVERSATION TRANSCRIPT", headerPaint)
        if (history.isEmpty()) {
            drawTextWrapped("No messages recorded.", bodyPaint)
        } else {
            history.forEach { msg ->
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.timestamp))
                val isAttacker = msg.senderType == "A"
                val prefix = if (isAttacker) "Scammer" else "Victim (App)"
                val paint = if (isAttacker) chatAttackerPaint else chatVictimPaint
                
                // Draw Sender + Time
                drawTextWrapped("[$time] $prefix:", paint)
                
                // Draw Message Content
                // slightly indent message content
                val contentLayout = StaticLayout.Builder.obtain(
                    msg.content, 0, msg.content.length, bodyPaint, (PAGE_WIDTH - 2 * MARGIN - 20f).toInt()
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, LINE_SPACING)
                    .setIncludePad(false)
                    .build()

                if (currentY + contentLayout.height > PAGE_HEIGHT - MARGIN) {
                    pdfDocument.finishPage(currentPage)
                    currentPage = pdfDocument.startPage(pageInfo)
                    canvas = currentPage.canvas
                    currentY = MARGIN
                }

                canvas.save()
                canvas.translate(MARGIN + 20f, currentY)
                contentLayout.draw(canvas)
                canvas.restore()
                
                currentY += contentLayout.height + 15f // Space between messages
            }
        }

        // Finish the last page
        finishPageWithFooter()
        pdfDocument.finishPage(currentPage)

        // Save to File
        val reportsDir = File(context.cacheDir, "reports")
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }
        
        val pdfFile = File(reportsDir, "CrimeReport_${sessionId}.pdf")
        FileOutputStream(pdfFile).use { out ->
            pdfDocument.writeTo(out)
        }
        
        pdfDocument.close()
        
        return pdfFile
    }
}
