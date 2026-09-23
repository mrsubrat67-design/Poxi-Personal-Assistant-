package com.example.poxi.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.example.poxi.model.ContactItem
import com.example.poxi.model.ToolActionInfo
import java.net.URLEncoder

sealed class CallContactOutcome {
    data class SingleMatch(val contact: ContactItem, val actionResult: BridgeResult) : CallContactOutcome()
    data class MultipleMatches(val query: String, val matches: List<ContactItem>) : CallContactOutcome()
    data class NotFound(val query: String, val message: String) : CallContactOutcome()
    data class PermissionDenied(val message: String) : CallContactOutcome()
}

data class BridgeResult(
    val success: Boolean,
    val summary: String,
    val detail: String? = null,
    val target: String? = null
)

class AndroidActionBridge(private val context: Context) {

    companion object {
        private const val TAG = "AndroidActionBridge"
        const val JS_INTERFACE_NAME = "AndroidBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks if native Android bridge is available and running.
     */
    @JavascriptInterface
    fun isNativeBridgeAvailable(): Boolean = true

    /**
     * Open WhatsApp or initiate chat.
     */
    @JavascriptInterface
    fun openWhatsApp(phoneNumber: String? = null, message: String? = null): String {
        val result = executeOpenWhatsApp(phoneNumber, message)
        return result.summary
    }

    fun executeOpenWhatsApp(phoneNumber: String? = null, message: String? = null): BridgeResult {
        return try {
            val pm = context.packageManager
            val whatsappPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
            var installedPkg: String? = null

            for (pkg in whatsappPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    installedPkg = pkg
                    break
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }

            if (installedPkg != null) {
                val intent: Intent
                if (!phoneNumber.isNullOrBlank()) {
                    val sanitized = sanitizePhoneNumber(phoneNumber)
                    val encodedMsg = if (!message.isNullOrBlank()) URLEncoder.encode(message, "UTF-8") else ""
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$sanitized&text=$encodedMsg")
                    intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage(installedPkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    intent = pm.getLaunchIntentForPackage(installedPkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    } ?: Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://app")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
                BridgeResult(
                    success = true,
                    summary = "WhatsApp opened successfully",
                    detail = if (!phoneNumber.isNullOrBlank()) "Chat with $phoneNumber" else "Main App",
                    target = "WhatsApp"
                )
            } else {
                // WhatsApp is not installed
                BridgeResult(
                    success = false,
                    summary = "WhatsApp is not installed on this device",
                    detail = "Package com.whatsapp not found",
                    target = "WhatsApp"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp", e)
            BridgeResult(
                success = false,
                summary = "Failed to open WhatsApp: ${e.message}",
                detail = e.localizedMessage,
                target = "WhatsApp"
            )
        }
    }

    /**
     * Open an app by common name or package.
     */
    @JavascriptInterface
    fun openApp(appName: String): String {
        val result = executeOpenApp(appName)
        return result.summary
    }

    fun executeOpenApp(appName: String): BridgeResult {
        val cleanName = appName.trim().lowercase()
        return try {
            when {
                cleanName.contains("whatsapp") || cleanName.contains("व्हाट्सएप") || cleanName.contains("वॉट्सएप") || cleanName.contains("व्हाट्सएप्प") -> executeOpenWhatsApp()
                cleanName.contains("youtube") || cleanName.contains("यूट्यूब") || cleanName.contains("यू ट्यूब") -> openYouTube()
                cleanName.contains("instagram") || cleanName.contains("insta") || cleanName.contains("इंस्टाग्राम") || cleanName.contains("इन्स्टाग्राम") -> openInstagram()
                cleanName.contains("chrome") || cleanName.contains("browser") || cleanName.contains("क्रोम") || cleanName.contains("ब्राउज़र") -> openChrome()
                cleanName.contains("setting") || cleanName.contains("सेटिंग") || cleanName.contains("सेटिंग्स") -> openSettings()
                cleanName.contains("camera") || cleanName.contains("कैमरा") -> openCamera()
                cleanName.contains("map") || cleanName.contains("मैप") || cleanName.contains("मैप्स") -> openMaps()
                cleanName.contains("clock") || cleanName.contains("alarm") || cleanName.contains("घड़ी") || cleanName.contains("अलार्म") -> openClock()
                cleanName.contains("contact") || cleanName.contains("संपर्क") || cleanName.contains("कॉन्टैक्ट्स") -> openContactsApp()
                cleanName.contains("dial") || cleanName.contains("phone") || cleanName.contains("फोन") || cleanName.contains("डायलर") -> openDialer()
                else -> launchAppByGeneralSearch(appName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $appName", e)
            BridgeResult(
                success = false,
                summary = "Could not open $appName: ${e.message}",
                detail = e.localizedMessage,
                target = appName
            )
        }
    }

    private fun openYouTube(): BridgeResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.google.android.youtube")
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            BridgeResult(true, "YouTube opened successfully", target = "YouTube")
        } else {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            BridgeResult(true, "Opened YouTube in browser", target = "YouTube")
        }
    }

    private fun openInstagram(): BridgeResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.instagram.android")
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            BridgeResult(true, "Instagram opened successfully", target = "Instagram")
        } else {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            BridgeResult(true, "Opened Instagram in browser", target = "Instagram")
        }
    }

    private fun openChrome(): BridgeResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.android.chrome")
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            BridgeResult(true, "Google Chrome opened successfully", target = "Chrome")
        } else {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            BridgeResult(true, "Default browser opened", target = "Browser")
        }
    }

    private fun openSettings(): BridgeResult {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return BridgeResult(true, "Device settings opened", target = "Settings")
    }

    private fun openCamera(): BridgeResult {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            BridgeResult(true, "Camera opened", target = "Camera")
        } catch (_: Exception) {
            val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            BridgeResult(true, "Camera opened", target = "Camera")
        }
    }

    private fun openMaps(): BridgeResult {
        val uri = Uri.parse("geo:0,0?q=")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            BridgeResult(true, "Google Maps opened", target = "Maps")
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            BridgeResult(true, "Opened Maps in browser", target = "Maps")
        }
    }

    private fun openClock(): BridgeResult {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return BridgeResult(true, "Clock & Alarms opened", target = "Clock")
    }

    private fun openContactsApp(): BridgeResult {
        val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return BridgeResult(true, "Contacts app opened", target = "Contacts")
    }

    private fun openDialer(): BridgeResult {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return BridgeResult(true, "Phone dialer opened", target = "Phone")
    }

    private fun launchAppByGeneralSearch(appName: String): BridgeResult {
        val pm = context.packageManager
        val cleanQuery = appName.trim().lowercase()
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in installedApps) {
            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
            if (label.contains(cleanQuery) || appInfo.packageName.lowercase().contains(cleanQuery)) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return BridgeResult(
                        success = true,
                        summary = "$label opened successfully",
                        detail = "Package: ${appInfo.packageName}",
                        target = label
                    )
                }
            }
        }

        return BridgeResult(
            success = false,
            summary = "App '$appName' was not found on this device",
            target = appName
        )
    }

    /**
     * Dials or initiates a phone call.
     */
    @JavascriptInterface
    fun makeCall(phoneNumber: String): String {
        val result = executeMakeCall(phoneNumber)
        return result.summary
    }

    fun executeMakeCall(phoneNumber: String): BridgeResult {
        val sanitized = sanitizePhoneNumber(phoneNumber)
        if (sanitized.isBlank()) {
            return BridgeResult(
                success = false,
                summary = "Invalid phone number provided",
                target = phoneNumber
            )
        }

        return try {
            // Check for CALL_PHONE permission
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = if (hasCallPermission) {
                // Direct call if explicitly permitted
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                // Safe dialer confirmation fallback
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)
            BridgeResult(
                success = true,
                summary = if (hasCallPermission) "Calling $sanitized" else "Opened dialer for $sanitized",
                detail = "Phone: $sanitized",
                target = sanitized
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make call", e)
            BridgeResult(
                success = false,
                summary = "Failed to start call: ${e.message}",
                detail = e.localizedMessage,
                target = sanitized
            )
        }
    }

    /**
     * Search contacts by name and initiate call or report matches.
     */
    @JavascriptInterface
    fun callContact(contactName: String): String {
        val outcome = executeCallContact(contactName)
        return when (outcome) {
            is CallContactOutcome.SingleMatch -> outcome.actionResult.summary
            is CallContactOutcome.MultipleMatches -> "Found ${outcome.matches.size} contacts for '${outcome.query}'"
            is CallContactOutcome.NotFound -> outcome.message
            is CallContactOutcome.PermissionDenied -> outcome.message
        }
    }

    fun executeCallContact(contactName: String): CallContactOutcome {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return CallContactOutcome.PermissionDenied(
                "Contacts permission is required to search contacts. Please allow contacts access."
            )
        }

        val cleanQuery = contactName.trim()
        if (cleanQuery.isBlank()) {
            return CallContactOutcome.NotFound(
                query = "",
                message = "Please specify a contact name to call."
            )
        }
        val expandedQueries = getRelationshipQueries(cleanQuery)

        val matches = queryContacts(expandedQueries)

        return when {
            matches.isEmpty() -> {
                CallContactOutcome.NotFound(
                    query = cleanQuery,
                    message = "No contact found with name '$cleanQuery'."
                )
            }
            matches.size == 1 -> {
                val single = matches.first()
                val callResult = executeMakeCall(single.phoneNumber)
                CallContactOutcome.SingleMatch(single, callResult)
            }
            else -> {
                // Multiple contacts found: disambiguation needed
                CallContactOutcome.MultipleMatches(
                    query = cleanQuery,
                    matches = matches.take(5)
                )
            }
        }
    }

    private fun getRelationshipQueries(input: String): List<String> {
        val lower = input.lowercase().trim()
        val queries = mutableListOf(input)
        val tokens = lower.split(Regex("[\\s,.-]+")).filter { it.isNotBlank() }

        fun matchesAny(aliases: List<String>): Boolean {
            return aliases.any { alias ->
                val aliasLower = alias.lowercase()
                lower == aliasLower || tokens.contains(aliasLower)
            }
        }

        val momAliases = listOf("mom", "mummy", "mother", "maa", "mataji", "ammi", "माँ", "मम्मी", "माताजी", "माता")
        val dadAliases = listOf("dad", "papa", "father", "pitaji", "abbu", "पापा", "पिताजी", "पिता")
        val brotherAliases = listOf("brother", "bro", "bhai", "bhaiya", "भाई", "भैया", "भाया")
        val sisterAliases = listOf("sister", "sis", "behen", "didi", "दीदी", "बहन")

        if (matchesAny(momAliases)) queries.addAll(listOf("Mom", "Mummy", "Mother", "Maa", "मम्मी", "माँ"))
        if (matchesAny(dadAliases)) queries.addAll(listOf("Dad", "Papa", "Father", "पापा", "पिताजी"))
        if (matchesAny(brotherAliases)) queries.addAll(listOf("Bhai", "Bhaiya", "Brother", "भाई", "भैया"))
        if (matchesAny(sisterAliases)) queries.addAll(listOf("Didi", "Behen", "Sister", "दीदी", "बहन"))

        return queries.distinct()
    }

    fun queryContacts(nameQueries: List<String>): List<ContactItem> {
        val results = mutableListOf<ContactItem>()
        val seenNumbers = mutableSetOf<String>()

        try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )

            for (query in nameQueries) {
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%$query%")

                contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                    while (cursor.moveToNext()) {
                        val id = if (idCol >= 0) cursor.getString(idCol) else ""
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else "Unknown"
                        val number = if (numCol >= 0) cursor.getString(numCol) else ""
                        val type = if (typeCol >= 0) cursor.getInt(typeCol) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE

                        val sanitized = sanitizePhoneNumber(number)
                        if (sanitized.isNotBlank() && !seenNumbers.contains(sanitized)) {
                            seenNumbers.add(sanitized)
                            val label = when (type) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                                else -> "Mobile"
                            }
                            results.add(ContactItem(id = id, name = name, phoneNumber = number, typeLabel = label))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        }

        return results
    }

    /**
     * Open an external URL.
     */
    @JavascriptInterface
    fun openUrl(url: String): String {
        val result = executeOpenUrl(url)
        return result.summary
    }

    fun executeOpenUrl(url: String): BridgeResult {
        var cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            BridgeResult(true, "Opened $cleanUrl", target = cleanUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL", e)
            BridgeResult(false, "Could not open URL: ${e.message}", target = cleanUrl)
        }
    }

    private fun sanitizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }
        return if (hasPlus) "+$digitsOnly" else digitsOnly
    }
}
