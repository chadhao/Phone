package dev.chadhao.phone.extensions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.extensions.canUseFullScreenIntent
import com.goodwy.commons.extensions.initiateCall
import com.goodwy.commons.extensions.isDefaultDialer
import com.goodwy.commons.extensions.launchCallIntent
import com.goodwy.commons.extensions.openFullScreenIntentSettings
import com.goodwy.commons.extensions.openNotificationSettings
import com.goodwy.commons.extensions.telecomManager
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import com.goodwy.commons.models.contacts.Contact
import dev.chadhao.phone.BuildConfig
import dev.chadhao.phone.activities.DialerActivity
import dev.chadhao.phone.activities.SimpleActivity
import dev.chadhao.phone.dialogs.SelectSIMDialog
import dev.chadhao.phone.dialogs.SelectSimButtonDialog
import dev.chadhao.phone.helpers.SIM_DIALOG_STYLE_LIST
import dev.chadhao.phone.helpers.ContactNameFormatter

fun SimpleActivity.startCallIntent(
    recipient: String,
    forceSimSelector: Boolean = false
) {
    if (isDefaultDialer()) {
        getHandleToUse(
            intent = null,
            phoneNumber = recipient,
            forceSimSelector = forceSimSelector
        ) { handle ->
            launchCallIntent(recipient, handle, BuildConfig.RIGHT_APP_KEY)
        }
    } else {
        launchCallIntent(recipient, null, BuildConfig.RIGHT_APP_KEY)
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(
    recipient: String,
    name: String,
    forceSimSelector: Boolean = false
) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            startCallIntent(recipient, forceSimSelector)
        }
    } else {
        startCallIntent(recipient, forceSimSelector)
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(contact: Contact) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(
            activity = this,
            callee = ContactNameFormatter.format(contact)
        ) {
            initiateCall(contact) { launchCallIntent(it, key = BuildConfig.RIGHT_APP_KEY) }
        }
    } else {
        initiateCall(contact) { launchCallIntent(it, key = BuildConfig.RIGHT_APP_KEY) }
    }
}

fun BaseSimpleActivity.callContactWithSim(
    recipient: String,
    useMainSIM: Boolean
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        val wantedSimIndex = if (useMainSIM) 0 else 1
        val handle = getAvailableSIMCardLabels()
            .sortedBy { it.id }
            .getOrNull(wantedSimIndex)?.handle
        launchCallIntent(recipient, handle, BuildConfig.RIGHT_APP_KEY)
    }
}

/**
 * Places a call using the call-capable account at the given zero-based index.
 * Added for the R4 per-SIM dialpad keys; keeps the boolean variant for existing callers.
 */
fun BaseSimpleActivity.callContactWithSim(
    recipient: String,
    simIndex: Int
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        val handle = getAvailableSIMCardLabels()
            .sortedBy { it.id }
            .getOrNull(simIndex)?.handle
        launchCallIntent(recipient, handle, BuildConfig.RIGHT_APP_KEY)
    }
}

fun BaseSimpleActivity.callContactWithSimWithConfirmationCheck(
    recipient: String,
    name: String,
    useMainSIM: Boolean
) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            callContactWithSim(recipient, useMainSIM)
        }
    } else {
        callContactWithSim(recipient, useMainSIM)
    }
}

/** Per-SIM confirmation variant matching [callContactWithSim] with an explicit account index. */
fun BaseSimpleActivity.callContactWithSimWithConfirmationCheck(
    recipient: String,
    name: String,
    simIndex: Int
) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            callContactWithSim(recipient, simIndex)
        }
    } else {
        callContactWithSim(recipient, simIndex)
    }
}

// used at devices with multiple SIM cards
@SuppressLint("MissingPermission")
fun SimpleActivity.getHandleToUse(
    intent: Intent?,
    phoneNumber: String,
    forceSimSelector: Boolean = false,
    callback: (handle: PhoneAccountHandle?) -> Unit
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        if (it) {
            val defaultHandle =
                telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            when {
                forceSimSelector -> showSelectSimDialog(phoneNumber, callback)
                intent?.hasExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE) == true -> {
                    callback(intent.getParcelableExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE)!!)
                }

                config.getCustomSIM(phoneNumber) != null && areMultipleSIMsAvailable() -> {
                    callback(config.getCustomSIM(phoneNumber))
                }

                defaultHandle != null -> callback(defaultHandle)
                else -> showSelectSimDialog(phoneNumber, callback)
            }
        }
    }
}

fun SimpleActivity.showSelectSimDialog(
    phoneNumber: String,
    callback: (handle: PhoneAccountHandle?) -> Unit
) {
    if (config.simDialogStyle == SIM_DIALOG_STYLE_LIST) {
        SelectSIMDialog(
            activity = this,
            phoneNumber = phoneNumber,
            onDismiss = {
                if (this is DialerActivity) {
                    finish()
                }
            }
        ) { handle, _ ->
            callback(handle)
        }
    } else {
        SelectSimButtonDialog(
            activity = this,
            phoneNumber = phoneNumber,
            onDismiss = {
                if (this is DialerActivity) {
                    finish()
                }
            }
        ) { handle, _ ->
            callback(handle)
        }
    }
}

fun SimpleActivity.handleFullScreenNotificationsPermission(callback: (granted: Boolean) -> Unit) {
    handleNotificationPermission { granted ->
        if (granted) {
            if (canUseFullScreenIntent()) {
                callback(true)
            } else {
                PermissionRequiredDialog(
                    activity = this,
                    textId = R.string.allow_full_screen_notifications_incoming_calls,
                    positiveActionCallback = {
                        @SuppressLint("NewApi")
                        openFullScreenIntentSettings(BuildConfig.APPLICATION_ID)
                    },
                    negativeActionCallback = {
                        callback(false)
                    }
                )
            }
        } else {
            PermissionRequiredDialog(
                activity = this,
                textId = R.string.allow_notifications_incoming_calls,
                positiveActionCallback = {
                    openNotificationSettings()
                },
                negativeActionCallback = {
                    callback(false)
                }
            )
        }
    }
}
