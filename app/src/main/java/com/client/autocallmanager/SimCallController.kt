package com.client.autocallmanager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Starts a SIM call and watches Android call state.
 * Android call-state events do not reliably establish whether a call was answered.
 */
class SimCallController(
    private val context: Context,
    private val onCallEnded: (String) -> Unit
) {
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var callWasOffHook = false
    private var callInProgress = false
    private var listenerRegistered = false
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val watchdog = Runnable {
        if (callInProgress && !callWasOffHook) {
            callInProgress = false
            unregisterListener()
            onCallEnded(
                "Call start was not detected within 45 seconds. " +
                        "The outcome is unverified; check the phone before continuing."
            )
        }
    }

    @Synchronized
    fun startCall(phone: String) {
        val number = phone.trim()
        require(number.isNotBlank()) { "Phone number is empty." }

        checkPermission(Manifest.permission.CALL_PHONE, "CALL_PHONE")
        checkPermission(Manifest.permission.READ_PHONE_STATE, "READ_PHONE_STATE")
        check(!callInProgress) { "A call is already being monitored." }

        callWasOffHook = false
        callInProgress = true

        try {
            registerListener()
            mainHandler.postDelayed(watchdog, 45_000L)

            val intent = Intent(
                Intent.ACTION_CALL,
                Uri.fromParts("tel", number, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            mainHandler.removeCallbacks(watchdog)
            callInProgress = false
            unregisterListener()
            throw e
        }
    }

    private fun checkPermission(permission: String, label: String) {
        check(
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        ) { "$label permission has not been granted." }
    }

    private fun registerListener() {
        if (listenerRegistered) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state)
                }
            }
            modernCallback = callback
            telephonyManager.registerTelephonyCallback(
                ContextCompat.getMainExecutor(context),
                callback
            )
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state)
                }
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        listenerRegistered = true
    }

    private fun handleState(state: Int) {
        if (!callInProgress) return

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callWasOffHook = true
                mainHandler.removeCallbacks(watchdog)
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (callWasOffHook) {
                    mainHandler.removeCallbacks(watchdog)
                    callInProgress = false
                    unregisterListener()
                    onCallEnded(
                        "Android detected that the call ended; " +
                                "the call outcome is unverified."
                    )
                }
            }
        }
    }

    private fun unregisterListener() {
        if (!listenerRegistered) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let(telephonyManager::unregisterTelephonyCallback)
            modernCallback = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let {
                @Suppress("DEPRECATION")
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
            legacyListener = null
        }
        listenerRegistered = false
    }

    @Synchronized
    fun cancelMonitoring() {
        mainHandler.removeCallbacks(watchdog)
        callInProgress = false
        unregisterListener()
    }
}
