package com.example.myapplication.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class IncomingSms(
    val sender: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val subscriptionId: Int = -1, // -1 means unknown, 1/2 means SIM slot
    val serviceCenterAddress: String = ""
) : Parcelable