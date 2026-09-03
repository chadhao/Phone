package dev.chadhao.phone.helpers

import android.content.Context
import android.view.View
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getTextSize
import dev.chadhao.phone.R
import dev.chadhao.phone.activities.SimpleActivity
import dev.chadhao.phone.databinding.DialpadBinding
import dev.chadhao.phone.databinding.DialpadSimCallKeyBinding
import dev.chadhao.phone.extensions.DialpadSim
import dev.chadhao.phone.extensions.config
import dev.chadhao.phone.extensions.getDialpadSimEntries

/**
 * Shared UI helpers for call-log rows and the contact-card page.
 *
 * Kept Phone-local on purpose (R2 ruling): typography constants live in the app res
 * (values/dimens.xml) instead of the shared commons fork, so other goodwy apps are
 * not affected by the two-tier hierarchy introduced here.
 */

/** Tolerance for a future-clocked device when deciding whether a timestamp is trustworthy. */
private const val CALL_LOG_TIME_SKEW_MS = 24L * 60 * 60 * 1000L

/**
 * Returns true only for a timestamp that can be rendered as real call time.
 *
 * DATE=0 / negative values (CallLog entries whose interception time is unknown)
 * would otherwise be formatted as 1970-01-01; clearly out-of-range values written
 * by buggy providers are rejected too (R1 ruling: never show 1970/1969 overflow).
 */
fun Long.isCallTimestampRenderable(): Boolean =
    this > 0L && this <= System.currentTimeMillis() + CALL_LOG_TIME_SKEW_MS

/**
 * Body-level text size (px) shared by every non-title text in call-log rows.
 *
 * The Phone-local body dim (14sp) is the baseline at the app's default "medium" font tier and is
 * scaled by (current title px / default title px) from the global in-app font-size setting, so body
 * text keeps following the in-app 4-tier font size (AC-R2-4) as well as the system font scale.
 */
fun Context.callLogBodyPx(): Float {
    val bodyBase = resources.getDimension(R.dimen.call_log_body_text_size)
    val defaultTitlePx = resources.getDimension(com.goodwy.commons.R.dimen.bigger_text_size)
    return bodyBase * (getTextSize() / defaultTitlePx)
}

/**
 * Renders the dialpad call area (R4): one key per call-capable account in the middle bottom
 * cell. With no account a single default-styled key is shown as a fallback.
 *
 * @param onCallClick invoked with the zero-based account index of the tapped key.
 * @param onCallLongClick optional long-press handler (paste/copy), attached to every key.
 */
fun DialpadBinding.renderDialpadCallKeys(
    activity: SimpleActivity,
    onCallClick: (index: Int) -> Unit,
    onCallLongClick: (() -> Unit)? = null
) {
    val container = dialpadCallContainer
    container.removeAllViews()
    val simEntries = activity.getDialpadSimEntries()

    if (simEntries.isEmpty()) {
        // No call-capable account: keep one default-styled key (existing no-SIM behaviour).
        // The key index is irrelevant here - initCall maps a zero index onto the no-SIM path.
        container.addView(buildDialpadCallKey(activity, sim = null, onCallClick = { onCallClick(0) }, onCallLongClick = onCallLongClick))
        return
    }

    simEntries.forEachIndexed { index, sim ->
        container.addView(
            buildDialpadCallKey(
                activity = activity,
                sim = sim,
                onCallClick = { onCallClick(index) },
                onCallLongClick = onCallLongClick
            )
        )
    }
}

private fun DialpadBinding.buildDialpadCallKey(
    activity: SimpleActivity,
    sim: DialpadSim?,
    onCallClick: (() -> Unit),
    onCallLongClick: (() -> Unit)?
): View {
    val keyBinding = DialpadSimCallKeyBinding.inflate(activity.layoutInflater, dialpadCallContainer, false)
    val properTextColor = activity.getProperTextColor()
    val fallbackColor = activity.config.simIconsColors[1].takeIf { it != 0 } ?: activity.getProperPrimaryColor()
    val keyColor = sim?.color?.takeIf { it != 0 } ?: fallbackColor
    val iconColor = keyColor.getContrastColor()

    keyBinding.simKeyIconHolder.background.setTint(keyColor)
    keyBinding.simKeyIcon.setColorFilter(iconColor)

    if (sim != null) {
        val iconRes = when (sim.id) {
            1 -> R.drawable.ic_phone_one_vector
            2 -> R.drawable.ic_phone_two_vector
            else -> R.drawable.ic_phone_vector
        }
        keyBinding.simKeyIcon.setImageResource(iconRes)

        keyBinding.simKeyLabel.apply {
            beVisible()
            text = sim.carrierName ?: sim.id.toString()
            setTextColor(properTextColor)
            contentDescription = null
        }
        keyBinding.root.contentDescription = if (sim.carrierName.isNullOrEmpty()) {
            activity.getString(R.string.call_with_sim_ordinal, sim.id)
        } else {
            activity.getString(R.string.call_with_sim_carrier, sim.carrierName, sim.id)
        }
    } else {
        keyBinding.simKeyLabel.beGone()
        keyBinding.root.contentDescription = activity.getString(com.goodwy.commons.R.string.call)
    }

    keyBinding.root.apply {
        setOnClickListener { onCallClick() }
        setOnLongClickListener {
            onCallLongClick?.invoke()
            true
        }
        isClickable = true
        isFocusable = true
    }
    return keyBinding.root
}
