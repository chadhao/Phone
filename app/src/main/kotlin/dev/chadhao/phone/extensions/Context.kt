package dev.chadhao.phone.extensions

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioManager.STREAM_ALARM
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.helpers.KEY_PHONE
import dev.chadhao.phone.R
import dev.chadhao.phone.models.SIMAccount
import dev.chadhao.phone.BuildConfig
import dev.chadhao.phone.activities.SplashActivity
import dev.chadhao.phone.databases.AppDatabase
import dev.chadhao.phone.helpers.*
import dev.chadhao.phone.interfaces.TimerDao
import dev.chadhao.phone.models.Timer
import dev.chadhao.phone.models.TimerState
import dev.chadhao.phone.receivers.TimerReceiver
import me.leolin.shortcutbadger.ShortcutBadger
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getDefaultAlarmSound
import com.goodwy.commons.extensions.getLaunchIntent
import com.goodwy.commons.extensions.getMyContactsCursor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.grantReadUriPermission
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.notificationManager
import com.goodwy.commons.extensions.sendSMSPendingIntent
import com.goodwy.commons.extensions.setText
import com.goodwy.commons.extensions.startCallPendingIntent
import com.goodwy.commons.extensions.telecomManager
import com.goodwy.commons.helpers.IS_RIGHT_APP
import com.goodwy.commons.helpers.MyContactsContentProvider
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import com.goodwy.commons.helpers.SIGNAL_PACKAGE
import com.goodwy.commons.helpers.SILENT
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.TELEGRAM_PACKAGE
import com.goodwy.commons.helpers.VIBER_PACKAGE
import com.goodwy.commons.helpers.WHATSAPP_PACKAGE
import com.goodwy.commons.models.SimpleContact

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager
    get() = getSystemService(Context.POWER_SERVICE) as PowerManager

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val simAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                label += " ($address)"
            }

            val simColor = try {
                config.simIconsColors[index + 1]
            } catch (_: Exception) {
                phoneAccount.highlightColor
            }
            simAccounts.add(
                SIMAccount(
                    id = index + 1,
                    handle = phoneAccount.accountHandle,
                    label = label,
                    phoneNumber = address.substringAfter("tel:"),
                    color = simColor
                )
            )
        }
    } catch (_: Exception) {
    }

    return simAccounts
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (_: Exception) {
        false
    }
}

/** A single outbound SIM account rendered as a dedicated dialpad call key (R4). */
data class DialpadSim(
    /** 1-based display order, mirrors SIMAccount.id used by the rest of the app. */
    val id: Int,
    /** Clean carrier/account label; null when unavailable or permission denied. */
    val carrierName: String?,
    /** Key colour taken from the user-configurable SIM icon colours. */
    val color: Int,
)

/**
 * Returns the accounts that can actually place calls (TelecomManager's
 * callCapablePhoneAccounts — the same source the app already uses for
 * multi-SIM detection), annotated with the clean carrier label for the key face.
 *
 * Degradation chain (R4):
 *  1. PhoneAccount.label with any "(address)" suffix stripped → carrier name;
 *  2. if the label is still empty and READ_PHONE_STATE is granted, fall back to
 *     SubscriptionManager.carrierName;
 *  3. otherwise carrierName stays null and the renderer shows the SIM ordinal.
 *
 * Exceptions (no telephony permission / not the default dialer / empty account
 * list) return an empty list — the dialpad then falls back to one default key.
 */
@SuppressLint("MissingPermission")
fun Context.getDialpadSimEntries(): List<DialpadSim> {
    val simEntries = mutableListOf<DialpadSim>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            val rawLabel = phoneAccount?.label?.toString().orEmpty()
            val carrierName = rawLabel.cleanSimLabel() ?: readSimCarrierNameFromSubscriptions(index)
            val color = try {
                config.simIconsColors[index + 1]
            } catch (_: Exception) {
                phoneAccount?.highlightColor ?: 0
            }
            simEntries.add(
                DialpadSim(
                    id = index + 1,
                    carrierName = carrierName,
                    color = color
                )
            )
        }
    } catch (_: Exception) {
    }

    return simEntries
}

/** Strips a PhoneAccount label down to the clean carrier/account name. */
private fun String.cleanSimLabel(): String? {
    var label = trim()
    if (label.isEmpty()) return null

    // Account labels frequently append the phone number, e.g. "中国移动 (138...)".
    val numberStart = label.indexOf('(')
    if (numberStart > 0 && label.endsWith(")")) {
        val candidate = label.substring(0, numberStart).trim()
        if (candidate.isNotEmpty()) label = candidate
    }
    return label.ifEmpty { null }
}

@SuppressLint("MissingPermission")
private fun Context.readSimCarrierNameFromSubscriptions(index: Int): String? {
    if (!hasPermission(PERMISSION_READ_PHONE_STATE)) return null
    return try {
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        activeSubscriptions.getOrNull(index)?.carrierName?.toString()?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

@SuppressLint("MissingPermission")
fun Context.clearMissedCalls() {
    try {
        // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
        // should update the database and reset the cached missed call count in MissedCallNotifier.java
        // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
//        telecomManager.cancelMissedCallsNotification()

//        notificationManager.cancelAll()
        updateUnreadCountBadge(0)
        RecentsHelper(this).markMissedCallsAsRead()
    } catch (_: Exception) {
    }
}

fun Context.canLaunchAccountsConfiguration(): Boolean {
    return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        .resolveActivity(packageManager) != null
}

fun Context.launchAccountsConfiguration() {
    startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
}

fun Activity.startAddContactIntent(phoneNumber: String) {
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, phoneNumber)
        launchActivityIntent(this)
    }
}

fun Context.updateUnreadCountBadge(count: Int) {
    if (count == 0) {
        ShortcutBadger.removeCount(this)
    } else {
        ShortcutBadger.applyCount(this, count)
    }
}

@SuppressLint("UseCompatLoadingForDrawables")
fun Context.getPackageDrawable(packageName: String): Drawable {
    return resources.getDrawable(
        when (packageName) {
            TELEGRAM_PACKAGE -> R.drawable.ic_telegram_vector
            SIGNAL_PACKAGE -> R.drawable.ic_signal_vector
            WHATSAPP_PACKAGE -> R.drawable.ic_whatsapp_vector
            VIBER_PACKAGE -> R.drawable.ic_viber_vector
            else -> R.drawable.ic_threema_vector
        }, theme
    )
}

// You need to run it in ensureBackgroundThread {}
fun Context.getShortcutImageNeedBackground(path: String, placeholderName: String, callback: (image: Bitmap) -> Unit) {
    val placeholder = SimpleContactsHelper(this).getContactLetterIcon(placeholderName).toDrawable(resources)
    try {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .error(placeholder)
            .centerCrop()

        val size = resources.getDimension(R.dimen.shortcut_size).toInt()
        val bitmap = Glide.with(this).asBitmap()
            .load(path)
            //.placeholder(placeholder)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
            .get()

        callback(bitmap)
    } catch (_: Exception) {
        callback(placeholder.bitmap)
    }
}

//Timer
val Context.timerDb: TimerDao
    get() = AppDatabase.getInstance(applicationContext).TimerDao()

val Context.timerHelper: TimerHelper
    get() = TimerHelper(this)

val Context.callerNotesHelper: CallerNotesHelper
    get() = CallerNotesHelper(this)

fun Context.getOpenTimerTabIntent(timerId: Int): PendingIntent {
    val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
    return PendingIntent.getActivity(
        this,
        timerId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun Context.hideNotification(id: Int) {
    notificationManager.cancel(id)
}

fun Context.getTimerNotification(timer: Timer, pendingIntent: PendingIntent): Notification {
    var soundUri = timer.soundUri
    if (soundUri == SILENT) {
        soundUri = ""
    } else {
        grantReadUriPermission(soundUri)
    }

    val channelId =
        timer.channelId ?: "right_dialer_timer_channel_${soundUri}_${System.currentTimeMillis()}"
    timerHelper.insertOrUpdateTimer(timer.copy(channelId = channelId))

    try {
        notificationManager.deleteNotificationChannel(channelId)
    } catch (_: Exception) {
    }

    val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setLegacyStreamType(STREAM_ALARM)
        .build()

    val name = getString(R.string.timer)
    val importance = NotificationManager.IMPORTANCE_HIGH
    NotificationChannel(channelId, name, importance).apply {
        setBypassDnd(true)
        enableLights(true)
        lightColor = getProperPrimaryColor()
        setSound(soundUri.toUri(), audioAttributes)

        if (!timer.vibrate) {
            vibrationPattern = longArrayOf(0L)
        }

        enableVibration(timer.vibrate)
        notificationManager.createNotificationChannel(this)
    }

    val restart = Intent(this, TimerReceiver::class.java).apply {
//        action = TIMER_RESTART
        putExtra(TIMER_ID, timer.id!!)
    }
    val cancelIntent = PendingIntent.getBroadcast(
        this, timer.id!!, restart, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val collapsedView = RemoteViews(this.packageName, R.layout.timer_notification).apply {
        setText(R.id.timer_title, getString(R.string.remind_me))
        setText(R.id.timer_content, String.format(getString(R.string.call_back_person_g), timer.title))
        setOnClickPendingIntent(R.id.timer_repeat, cancelIntent)
    }

    val builder = NotificationCompat.Builder(this, channelId)
//        .setContentTitle(getString(R.string.remind_me))
//        .setContentText(String.format(getString(R.string.call_back_person_g), timer.title))
        .setCategory(Notification.CATEGORY_REMINDER)
        .setCustomContentView(collapsedView)
        .setSmallIcon(R.drawable.ic_remind_call)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setDefaults(Notification.DEFAULT_LIGHTS)
        .setSound(soundUri.toUri(), STREAM_ALARM)
        .setChannelId(channelId)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .addAction(
            com.goodwy.commons.R.drawable.ic_cross_vector,
            getString(com.goodwy.commons.R.string.dismiss),
            getHideTimerPendingIntent(timer.id!!)
        )
        .addAction(
            R.drawable.ic_messages,
            getString(R.string.message),
            sendSMSPendingIntent(timer.label)
        )
        .addAction(
            R.drawable.ic_phone_vector,
            getString(R.string.call_back_g),
            startCallPendingIntent(timer.label, BuildConfig.RIGHT_APP_KEY)
        )

    builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    if (timer.vibrate) {
        val vibrateArray = LongArray(2) { 500 }
        builder.setVibrate(vibrateArray)
    }

    val notification = builder.build()
    notification.flags = notification.flags or Notification.FLAG_INSISTENT
    return notification
}

fun Context.getHideTimerPendingIntent(timerId: Int): PendingIntent {
    val intent = Intent(this, TimerReceiver::class.java)
    intent.action = TIMER_HIDE
    intent.putExtra(TIMER_ID, timerId)
    return PendingIntent.getBroadcast(
        this,
        timerId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

fun Context.startCallPendingIntentUpdateCurrent(recipient: String): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(Intent.ACTION_CALL, Uri.fromParts("tel", recipient, null))
            .putExtra(IS_RIGHT_APP, BuildConfig.RIGHT_APP_KEY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.sendSMSPendingIntentUpdateCurrent(recipient: String): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", recipient, null)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun Context.hideTimerNotification(timerId: Int) = hideNotification(timerId)

fun Context.createNewTimer(): Timer {
    return Timer(
        id = 1,
        seconds = 600,
        state = TimerState.Idle,
        vibrate = config.callVibration,
        soundUri = getDefaultAlarmSound(RingtoneManager.TYPE_ALARM).uri,
        soundTitle = "",
        title = "Timer",
        label = "",
        description = "",
        createdAt = System.currentTimeMillis(),
        channelId = "right_dialer_timer_channel",
    )
}

fun Context.getNotificationBitmap(photoUri: String): Bitmap? {
    val size = resources.getDimension(R.dimen.contact_photo_size).toInt()
    if (photoUri.isEmpty()) {
        return null
    }

    val options = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .centerCrop()

    return try {
        Glide.with(this)
            .asBitmap()
            .load(photoUri)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
            .get()
    } catch (_: Exception) {
        null
    }
}

val Context.statusBarHeight: Int
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    get() {
        var statusBarHeight = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = resources.getDimensionPixelSize(resourceId)
        }
        return statusBarHeight
    }

val Context.navigationBarHeight: Int
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    get() {
        var navigationBarHeight = 0
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            navigationBarHeight = resources.getDimensionPixelSize(resourceId)
        }
        return navigationBarHeight
    }

fun Context.getContactFromAddress(address: String, callback: ((contact: SimpleContact?) -> Unit)) {
    val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
    SimpleContactsHelper(this).getAvailableContacts(false) {
        var contact = it.firstOrNull { it.doesHavePhoneNumber(address) }
        if (contact == null) {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }
            contact = privateContact
        }
        if (contact == null) {
            contact = it.firstOrNull { it.phoneNumbers.map { it.value }.any { it == address } }
        }
        callback(contact)
    }
}

fun Context.getDialpadButtonsColor(): Int {
    return when (config.dialpadButtonsColorStyle) {
        DIALPAD_BUTTONS_STYLE_COLOR -> config.dialpadButtonsColor
        DIALPAD_BUTTONS_STYLE_TRANSPARENT -> if (isDynamicTheme() && !isSystemInDarkMode()) getProperBackgroundColor() else getSurfaceColor()
        else -> if (isDynamicTheme() && !isSystemInDarkMode()) getSurfaceColor() else getProperBackgroundColor()
    }
}
