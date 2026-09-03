package dev.chadhao.phone.extensions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.goodwy.commons.extensions.getContactPublicUri
import com.goodwy.commons.extensions.isPackageInstalled
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.launchSendSMSIntent
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import com.goodwy.commons.helpers.LICENSE_EVENT_BUS
import com.goodwy.commons.helpers.LICENSE_GLIDE
import com.goodwy.commons.helpers.LICENSE_INDICATOR_FAST_SCROLL
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.FAQItem
import com.goodwy.commons.models.contacts.Contact
import dev.chadhao.phone.BuildConfig
import dev.chadhao.phone.R
import dev.chadhao.phone.activities.SimpleActivity
import androidx.core.net.toUri

fun SimpleActivity.launchCreateNewContactIntent() {
    Intent().apply {
        action = Intent.ACTION_INSERT
        data = ContactsContract.Contacts.CONTENT_URI
        launchActivityIntent(this)
    }
}

// open contact details through the system QuickContact sheet
fun Activity.startContactDetailsIntent(contact: Contact) {
    ensureBackgroundThread {
        val lookupKey =
            SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
        val publicUri =
            Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
        runOnUiThread {
            Intent().apply {
                action = ContactsContract.QuickContact.ACTION_QUICK_CONTACT
                data = publicUri
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                launchActivityIntent(this)
            }
        }
    }
}

//Goodwy
fun Activity.startContactEdit(contact: Contact) {
    Intent().apply {
        action = Intent.ACTION_EDIT
        data = getContactPublicUri(contact)
        launchActivityIntent(this)
    }
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_GLIDE or LICENSE_INDICATOR_FAST_SCROLL or LICENSE_AUTOFITTEXTVIEW or LICENSE_EVENT_BUS

    val faqItems = arrayListOf(
        FAQItem(R.string.faq_1_title, R.string.faq_1_text),
        FAQItem(R.string.faq_2_title, R.string.faq_2_text),
        FAQItem(R.string.faq_3_title, R.string.faq_3_text_g),
        FAQItem(R.string.faq_1_title_dialer_g, R.string.faq_1_text_dialer_g),
        FAQItem(R.string.faq_2_title_dialer_g, R.string.faq_2_text_dialer_g),
        FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons_g),
        FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons_g),
        FAQItem(R.string.faq_7_title_commons, R.string.faq_7_text_commons),
        FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
    )

    val versionName = BuildConfig.VERSION_NAME

    startAboutActivity(
        appNameId = R.string.app_name,
        licenseMask = licenses,
        versionName = versionName,
        flavorName = BuildConfig.FLAVOR,
        faqItems = faqItems,
        showFAQBeforeMail = true,
        productIdList = arrayListOf(),
        productIdListRu = arrayListOf(),
        subscriptionIdList = arrayListOf(),
        subscriptionIdListRu = arrayListOf(),
        subscriptionYearIdList = arrayListOf(),
        subscriptionYearIdListRu = arrayListOf(),
    )
}

fun Activity.launchSendSMSIntentRecommendation(recipient: String) {
    launchSendSMSIntent(recipient)
}

fun Activity.launchSendWhatsAppIntent(phoneNumber: String) {
    val digits = phoneNumber.filter { it.isDigit() }
    if (digits.isEmpty()) {
        toast(R.string.no_app_found)
        return
    }
    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isPackageInstalled(it) }
    val intent = Intent(Intent.ACTION_VIEW, "https://wa.me/$digits".toUri())
    if (pkg != null) intent.setPackage(pkg)
    try {
        startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        toast(R.string.no_app_found)
    }
}

fun Activity.startContactDetailsIntentRecommendation(contact: Contact) {
    startContactDetailsIntent(contact)
}
