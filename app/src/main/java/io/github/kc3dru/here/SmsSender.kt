package io.github.kc3dru.here

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger

object SmsSender {
    private const val TAG = "SmsSender"

    /** Broadcast action sent back to this app when the radio accepts/rejects a send. */
    const val ACTION_SMS_SENT = "io.github.kc3dru.here.SMS_SENT"

    /** Recipient carried by [ACTION_SMS_SENT]. */
    const val EXTRA_RECIPIENT = "recipient"

    /** Monotonic counter so sent PendingIntents never collide with each other. */
    private val sentRequestCode = AtomicInteger(0)

    /**
     * Builds the per-call sent-notification PendingIntent: private to this
     * package, carrying the recipient, with a unique request code.
     */
    private fun createSentPendingIntent(context: Context, phoneNumber: String): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_RECIPIENT, phoneNumber)
        }
        return PendingIntent.getBroadcast(
            context,
            sentRequestCode.getAndIncrement(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Sends an SMS message to the specified phone number.
     *
     * @param context Application context
     * @param phoneNumber The recipient's phone number (with country code, e.g., "+1234567890")
     * @param message The message text to send
     * @return true if the message was queued for sending, false otherwise
     */
    fun sendSms(context: Context, phoneNumber: String, message: String): Result<Boolean> {
        return try {
            val sanitizedNumber = phoneNumber.trim()
            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)

            // Sent acknowledgement for both paths (unique request code per call)
            val sentPendingIntent = createSentPendingIntent(context, sanitizedNumber)

            // Check message length and split if necessary (SMS limit is 160 chars for GSM-7)
            val parts = if (message.length > 160) {
                val divided = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(
                    sanitizedNumber, null, divided,
                    ArrayList(divided.map { sentPendingIntent }), null
                )
                true
            } else {
                smsManager.sendTextMessage(
                    sanitizedNumber, null, message, null, sentPendingIntent
                )
                true
            }

            Log.i(TAG, "SMS queued")
            Result.success(parts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            Result.failure(e)
        }
    }

    /**
     * Creates a Google Maps URL from latitude and longitude.
     */
    fun createGoogleMapsUrl(lat: Double, lng: Double): String {
        return "https://maps.google.com/?q=$lat,$lng"
    }

    /**
     * Creates the "I am here" message with the Google Maps URL.
     */
    fun createArrivedMessage(lat: Double, lng: Double): String {
        val url = createGoogleMapsUrl(lat, lng)
        return "I am here. $url"
    }

    /**
     * Creates the "I am heading home" message with the Google Maps URL.
     */
    fun createHeadingHomeMessage(lat: Double, lng: Double): String {
        val url = createGoogleMapsUrl(lat, lng)
        return "I am heading home from here. $url"
    }

    /**
     * Creates the "Moving on to the next stop" message with the Google Maps URL.
     */
    fun createMovingOnMessage(lat: Double, lng: Double): String {
        return "Moving on to the next stop, leaving from here " + createGoogleMapsUrl(lat, lng)
    }
}
