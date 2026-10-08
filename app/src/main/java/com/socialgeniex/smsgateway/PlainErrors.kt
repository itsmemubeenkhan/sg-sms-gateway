package com.socialgeniex.smsgateway

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Every error the user sees is plain language.
 * No HTTP codes, no jargon ("FCM", "poll interval", "subscription ID", …).
 */
object PlainErrors {
    fun msg(t: Throwable): String = when (t) {
        is ApiException -> when (t.code) {
            401, 403 -> "Link expired — scan the QR code again"
            404 -> "Server doesn't recognize this phone — scan the QR code again"
            in 500..599 -> "SocialGeniex had a problem — try again in a bit"
            else -> "Something went wrong — please try again"
        }
        is UnknownHostException, is ConnectException ->
            "Phone is offline — check your internet connection"
        is SocketTimeoutException ->
            "Server is taking too long — try again"
        else -> "Something went wrong — please try again"
    }
}
