package com.example.repository

data class CallOffer(
    val callerId: String,
    val callerName: String,
    val callerIp: String,
    val isVideoCall: Boolean
)
