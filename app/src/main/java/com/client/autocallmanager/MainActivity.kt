package com.client.autocallmanager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SPREADSHEET_ID =
    "1Xjjwq7t8r7Cv6YFmF3U_VEw5mwLCaLcxXSoYqdF09wE"

private const val SHEETS_SCOPE =
    "https://www.googleapis.com/auth/spreadsheets"

private const val SHEET_RANGE = "Sheet1%21A%3AE"

private val Navy = Color(0xFF0B1220)
private val CardColor = Color(0xFF1C293B)
private val Accent = Color(0xFF20D6A0)
private val Muted = Color(0xFFB6C2D2)
private val Danger = Color(0xFFFF6B6B)
private val Purple = Color(0xFF8B6CF6)
private val InputColor = Color(0xFF253449)
private val BorderColor = Color(0xFF53657D)
private val DisabledColor = Color(0xFF35445A)
private val Warning = Color(0xFFFFC857)

data class SheetContact(
    val name: String,
    val phone: String,
    val callDate: String,
    val callTime: String,
    val status: String
)

data class CallHistoryEntry(
    val id: Long,
    val name: String,
    val phone: String,
    val dateTime: String,
    val outcome: String
)

private const val CALL_HISTORY_PREFS = "autocall_call_history"
private const val CALL_HISTORY_KEY = "entries_json"
private const val MAX_CALL_HISTORY = 200

private enum class QueueState {
    STOPPED, CALLING, BREAK, PAUSED, WAITING, COMPLETED
}

class MainActivity : ComponentActivity() {

    private val httpClient = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()

    private var sheetConnected by mutableStateOf(false)
    private var isLoading by mutableStateOf(false)
    private var connectionMessage by mutableStateOf("Not connected")
    private var contacts by mutableStateOf<List<SheetContact>>(emptyList())
    private var callHistory by mutableStateOf<List<CallHistoryEntry>>(emptyList())

    private var callPermissionsGranted by mutableStateOf(false)
    private var callEvent by mutableIntStateOf(0)
    private var callEventMessage by mutableStateOf("")
    private var callStartError by mutableStateOf("")
    private var sheetsAccessToken: String = ""

    private lateinit var authorizationLauncher:
            androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>

    private lateinit var callPermissionLauncher:
            androidx.activity.result.ActivityResultLauncher<Array<String>>

    private val callController by lazy {
        SimCallController(this) { message ->
            runOnUiThread {
                callEventMessage = message
                updateLatestHistoryOutcome(message)
                callEvent++
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callPermissionsGranted = hasCallPermissions()
        callHistory = readCallHistory()

        authorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                try {
                    val authResult =
                        Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(result.data)

                    val token = authResult.accessToken

                    if (!token.isNullOrBlank()) {
                        loadContacts(token)
                    } else {
                        showError("Google did not return an access token.")
                    }
                } catch (e: ApiException) {
                    showError(
                        "Authorization failed: ${e.statusCode}. " +
                                (e.message ?: "Please try again.")
                    )
                } catch (e: Exception) {
                    showError(
                        "Authorization error: " +
                                (e.message ?: "Please try again.")
                    )
                }
            } else {
                showError("Google Sheets permission was cancelled.")
            }
        }

        callPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            callPermissionsGranted =
                result[Manifest.permission.CALL_PHONE] == true &&
                        result[Manifest.permission.READ_PHONE_STATE] == true

            callStartError = if (!callPermissionsGranted) {
                "Calling permissions were not granted. Enable them in Android Settings."
            } else {
                ""
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Purple,
                    onPrimary = Color.White,
                    secondary = Accent,
                    onSecondary = Navy,
                    background = Navy,
                    onBackground = Color.White,
                    surface = CardColor,
                    onSurface = Color.White,
                    surfaceVariant = InputColor,
                    onSurfaceVariant = Muted,
                    outline = BorderColor,
                    error = Danger,
                    onError = Color.White
                )
            ) {
                AutoCallDashboard(
                    sheetConnected = sheetConnected,
                    isLoading = isLoading,
                    connectionMessage = connectionMessage,
                    contacts = contacts,
                    callHistory = callHistory,
                    onClearCallHistory = { clearCallHistory() },
                    callPermissionsGranted = callPermissionsGranted,
                    callEvent = callEvent,
                    callEventMessage = callEventMessage,
                    callStartError = callStartError,
                    onConnect = { connectGoogleSheets() },
                    onRequestPermissions = { requestCallPermissions() },
                    onUpdateSheetResult = { index, status ->
                        updateSheetCallResult(index, status)
                    },
                    onStartCall = { phone ->
                        try {
                            callStartError = ""
                            callController.startCall(phone)
                            recordCallStarted(phone)
                        } catch (e: Exception) {
                            callStartError =
                                e.message ?: "Could not start call."
                        }
                    },
                    onCancelMonitoring = {
                        callController.cancelMonitoring()
                    },
                    onClearCallError = {
                        callStartError = ""
                    }
                )
            }
        }
    }

    private fun hasCallPermissions(): Boolean {
        val callPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val statePermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return callPermission && statePermission
    }

    private fun requestCallPermissions() {
        if (hasCallPermissions()) {
            callPermissionsGranted = true
        } else {
            callPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
                )
            )
        }
    }

    private fun connectGoogleSheets() {
        if (isLoading) return

        isLoading = true
        connectionMessage = "Requesting Google Sheets access..."

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pending = result.pendingIntent

                    if (pending != null) {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(
                                pending.intentSender
                            ).build()
                        )
                    } else {
                        showError(
                            "Google authorization needs to be retried."
                        )
                    }
                } else {
                    val token = result.accessToken

                    if (!token.isNullOrBlank()) {
                        loadContacts(token)
                    } else {
                        showError(
                            "Google did not return an access token."
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                showError(
                    "Could not request Google access: " +
                            (e.message ?: "Please try again.")
                )
            }
    }
    private fun recordCallStarted(phone: String) {
        val normalizedPhone = phone.trim()
        val contact = contacts.firstOrNull { it.phone.trim() == normalizedPhone }
        val entry = CallHistoryEntry(
            id = System.currentTimeMillis(),
            name = contact?.name?.takeIf { it.isNotBlank() } ?: "Unknown contact",
            phone = normalizedPhone,
            dateTime = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
            ).format(Date()),
            outcome = "Call requested — outcome pending"
        )
        callHistory = (listOf(entry) + callHistory).take(MAX_CALL_HISTORY)
        persistCallHistory(callHistory)
    }

    private fun updateLatestHistoryOutcome(message: String) {
        val latest = callHistory.firstOrNull() ?: return
        if (!latest.outcome.contains("pending", ignoreCase = true)) return
        val outcome = if (
            message.contains("not detected", ignoreCase = true) ||
            message.contains("could not be verified", ignoreCase = true) ||
            message.contains("unverified", ignoreCase = true)
        ) {
            "Attempted — outcome unverified"
        } else {
            "Call monitoring ended — outcome unverified"
        }
        callHistory = callHistory.mapIndexed { index, entry ->
            if (index == 0) entry.copy(outcome = outcome) else entry
        }
        persistCallHistory(callHistory)
    }

    private fun readCallHistory(): List<CallHistoryEntry> {
        return try {
            val raw = getSharedPreferences(CALL_HISTORY_PREFS, MODE_PRIVATE)
                .getString(CALL_HISTORY_KEY, "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                CallHistoryEntry(
                    id = item.optLong("id"),
                    name = item.optString("name", "Unknown contact"),
                    phone = item.optString("phone"),
                    dateTime = item.optString("dateTime"),
                    outcome = item.optString("outcome", "Outcome unverified")
                )
            }.take(MAX_CALL_HISTORY)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistCallHistory(entries: List<CallHistoryEntry>) {
        val array = JSONArray()
        entries.take(MAX_CALL_HISTORY).forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("phone", entry.phone)
                    .put("dateTime", entry.dateTime)
                    .put("outcome", entry.outcome)
            )
        }
        getSharedPreferences(CALL_HISTORY_PREFS, MODE_PRIVATE)
            .edit()
            .putString(CALL_HISTORY_KEY, array.toString())
            .apply()
    }

    private fun clearCallHistory() {
        callHistory = emptyList()
        getSharedPreferences(CALL_HISTORY_PREFS, MODE_PRIVATE)
            .edit()
            .remove(CALL_HISTORY_KEY)
            .apply()
    }

    private fun updateSheetCallResult(
        contactIndex: Int,
        status: String
    ) {
        if (contactIndex < 0 || contactIndex >= contacts.size) return

        val token = sheetsAccessToken
        if (token.isBlank()) {
            runOnUiThread {
                connectionMessage =
                    "Call result could not be saved: reconnect Google Sheets."
            }
            return
        }

        val sheetRow = contactIndex + 2

        val date = SimpleDateFormat(
            "yyyy-MM-dd", Locale.getDefault()
        ).format(Date())

        val time = SimpleDateFormat(
            "HH:mm:ss", Locale.getDefault()
        ).format(Date())

        executor.execute {
            try {
                val values = JSONArray().put(
                    JSONArray()
                        .put(date)
                        .put(time)
                        .put(status)
                )

                val body = JSONObject()
                    .put("range", "Sheet1!C$sheetRow:E$sheetRow")
                    .put("majorDimension", "ROWS")
                    .put("values", values)
                    .toString()
                    .toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )

                val range = "Sheet1!C$sheetRow:E$sheetRow"
                val encodedRange =
                    java.net.URLEncoder.encode(range, "UTF-8")

                val url =
                    "https://sheets.googleapis.com/v4/spreadsheets/" +
                            "$SPREADSHEET_ID/values/$encodedRange" +
                            "?valueInputOption=RAW"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .put(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = response.body?.string().orEmpty()
                        throw Exception(
                            "Sheets update failed (HTTP ${response.code}): " +
                                    error.take(250)
                        )
                    }
                }

                runOnUiThread {
                    connectionMessage = "Call result saved to Google Sheets."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectionMessage =
                        "Call detected, but Sheets update failed: " +
                                (e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun loadContacts(accessToken: String) {
        isLoading = true
        connectionMessage = "Loading contacts from Google Sheets..."

        executor.execute {
            try {
                val url =
                    "https://sheets.googleapis.com/v4/spreadsheets/" +
                            "$SPREADSHEET_ID/values/$SHEET_RANGE"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        throw Exception(
                            "Sheets API returned HTTP ${response.code}: " +
                                    body.take(300)
                        )
                    }

                    val rows = JSONObject(body).optJSONArray("values")
                    val loaded = mutableListOf<SheetContact>()

                    if (rows != null && rows.length() > 1) {
                        for (i in 1 until rows.length()) {
                            val row = rows.optJSONArray(i) ?: continue

                            fun cell(column: Int): String {
                                return if (column < row.length()) {
                                    row.optString(column, "").trim()
                                } else {
                                    ""
                                }
                            }

                            val contact = SheetContact(
                                name = cell(0),
                                phone = cell(1),
                                callDate = cell(2),
                                callTime = cell(3),
                                status = cell(4)
                            )

                            if (
                                listOf(
                                    contact.name,
                                    contact.phone,
                                    contact.callDate,
                                    contact.callTime,
                                    contact.status
                                ).any { it.isNotBlank() }
                            ) {
                                loaded.add(contact)
                            }
                        }
                    }

                    runOnUiThread {
                        contacts = loaded
                        sheetConnected = true
                        connectionMessage =
                            "Connected successfully. Read/write access requested."
                        isLoading = false
                        sheetsAccessToken = accessToken
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    sheetConnected = false
                    connectionMessage =
                        "Could not load the sheet: " +
                                (e.message ?: "Unknown error")
                    isLoading = false
                }
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            isLoading = false
            sheetConnected = false
            connectionMessage = message
        }
    }

    override fun onDestroy() {
        callController.cancelMonitoring()
        executor.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun AutoCallDashboard(
    sheetConnected: Boolean,
    isLoading: Boolean,
    connectionMessage: String,
    contacts: List<SheetContact>,
    callHistory: List<CallHistoryEntry>,
    onClearCallHistory: () -> Unit,
    callPermissionsGranted: Boolean,
    callEvent: Int,
    callEventMessage: String,
    callStartError: String,
    onConnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    onUpdateSheetResult: (Int, String) -> Unit,
    onStartCall: (String) -> Unit,
    onCancelMonitoring: () -> Unit,
    onClearCallError: () -> Unit
) {
    var breakDuration by remember { mutableStateOf("180") }
    var state by remember { mutableStateOf(QueueState.STOPPED) }
    var pausedFrom by remember { mutableStateOf(QueueState.STOPPED) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var seconds by remember { mutableIntStateOf(0) }
    var successes by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableStateOf(false) }
    var launchedIndex by remember { mutableIntStateOf(-1) }
    var lastEvent by remember { mutableIntStateOf(0) }
    var statusMessage by remember {
        mutableStateOf("Connect Google Sheets to begin.")
    }

    val eligible = contacts.filter { contact ->
        contact.phone.isNotBlank() &&
                listOf(
                    "Completed",
                    "Called",
                    "Done",
                    "Attempted - outcome unverified"
                ).none {
                    contact.status.equals(it, ignoreCase = true)
                }
    }

    val current = contacts.getOrNull(currentIndex)

    val breakValid = breakDuration.toIntOrNull()
        ?.let { it in 0..86400 } == true

    fun nextIndex(from: Int): Int {
        for (i in from until contacts.size) {
            val contact = contacts[i]

            val alreadyHandled = listOf(
                "Completed",
                "Called",
                "Done",
                "Attempted - outcome unverified"
            ).any {
                contact.status.equals(it, ignoreCase = true)
            }

            if (contact.phone.isNotBlank() && !alreadyHandled) {
                return i
            }
        }
        return -1
    }

    fun beginQueue() {
        if (!sheetConnected || contacts.isEmpty()) {
            statusMessage = "Connect Google Sheets and load contacts first."
            return
        }

        if (!callPermissionsGranted) {
            pendingStart = true
            onRequestPermissions()
            return
        }

        successes = 0
        failures = 0
        seconds = 0
        launchedIndex = -1

        val first = nextIndex(0)

        if (first < 0) {
            currentIndex = -1
            state = QueueState.COMPLETED
            statusMessage = "No eligible contacts."
        } else {
            currentIndex = first
            state = QueueState.CALLING
            statusMessage = "Preparing the first call."
        }

        pendingStart = false
    }

    fun stopQueue() {
        state = QueueState.STOPPED
        currentIndex = -1
        seconds = 0
        pendingStart = false
        launchedIndex = -1
        onCancelMonitoring()
        statusMessage =
            "Queue stopped. Hang up an active call manually if needed."
    }

    fun reviewCall(success: Boolean) {
        if (state != QueueState.WAITING) return

        if (success) successes++ else failures++

        val next = nextIndex(currentIndex + 1)

        launchedIndex = -1
        onClearCallError()

        if (next < 0) {
            currentIndex = -1
            seconds = 0
            state = QueueState.COMPLETED
            statusMessage = "Queue completed."
        } else {
            currentIndex = next
            seconds = breakDuration.toIntOrNull()
                ?.coerceIn(0, 86400) ?: 180

            state = if (seconds == 0) {
                QueueState.CALLING
            } else {
                QueueState.BREAK
            }

            statusMessage = if (seconds == 0) {
                "Preparing next call."
            } else {
                "Waiting before next call."
            }
        }
    }
    LaunchedEffect(callPermissionsGranted, pendingStart) {
        if (pendingStart && callPermissionsGranted) {
            beginQueue()
        }
    }

    LaunchedEffect(state, currentIndex, callPermissionsGranted) {
        if (
            state == QueueState.CALLING &&
            currentIndex >= 0 &&
            callPermissionsGranted &&
            launchedIndex != currentIndex
        ) {
            val contact = contacts.getOrNull(currentIndex)

            if (contact == null) {
                state = QueueState.WAITING
                statusMessage = "Contact unavailable; review the queue."
            } else {
                launchedIndex = currentIndex
                statusMessage =
                    "Dial request sent. Keep the app available in foreground."
                onStartCall(contact.phone)
            }
        }
    }

    LaunchedEffect(callEvent) {
        if (callEvent > lastEvent) {
            lastEvent = callEvent

            if (state == QueueState.CALLING || state == QueueState.PAUSED) {
                val endedIndex = currentIndex
                launchedIndex = -1

                if (
                    callEventMessage.contains("not detected", ignoreCase = true) ||
                    callEventMessage.contains(
                        "could not be verified",
                        ignoreCase = true
                    )
                ) {
                    state = QueueState.WAITING
                    statusMessage =
                        "$callEventMessage Check the phone before continuing."
                } else if (
                    endedIndex < 0 ||
                    contacts.getOrNull(endedIndex) == null
                ) {
                    state = QueueState.WAITING
                    statusMessage =
                        "Call event received, but the current contact could not be identified."
                } else {
                    onUpdateSheetResult(
                        endedIndex,
                        "Attempted - outcome unverified"
                    )

                    val next = nextIndex(endedIndex + 1)

                    if (next < 0) {
                        currentIndex = -1
                        seconds = 0
                        state = QueueState.COMPLETED
                        statusMessage =
                            "Queue completed. The last call attempt was recorded."
                    } else {
                        currentIndex = next
                        seconds = breakDuration.toIntOrNull()
                            ?.coerceIn(0, 86400) ?: 180

                        state = if (seconds == 0) {
                            QueueState.CALLING
                        } else {
                            QueueState.BREAK
                        }

                        statusMessage =
                            "Call ended (outcome unverified). Next call follows the configured break."
                    }
                }
            }
        }
    }

    LaunchedEffect(callStartError) {
        if (callStartError.isNotBlank()) {
            if (state == QueueState.CALLING) {
                state = QueueState.WAITING
                launchedIndex = -1
            }
            statusMessage = callStartError
        }
    }

    LaunchedEffect(state, seconds) {
        if (state == QueueState.BREAK) {
            if (seconds > 0) {
                delay(1000)
                seconds--
            } else {
                state = QueueState.CALLING
                statusMessage = "Break finished."
            }
        }
    }

    val stateText = when (state) {
        QueueState.STOPPED -> "Stopped"
        QueueState.CALLING -> "Call requested / in progress"
        QueueState.BREAK -> "Break"
        QueueState.PAUSED -> "Paused"
        QueueState.WAITING -> "Needs review"
        QueueState.COMPLETED -> "Queue completed"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Navy
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Accent
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                "☎",
                                color = Navy,
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "AutoCallManager",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Text(
                            "Smart calling dashboard",
                            color = Muted,
                            fontSize = 13.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (sheetConnected) {
                            Color(0xFF123D32)
                        } else {
                            CardColor
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(7.dp),
                                shape = RoundedCornerShape(50),
                                color = if (sheetConnected) Accent else Muted
                            ) {}

                            Text(
                                if (sheetConnected) "Online" else "Offline",
                                color = if (sheetConnected) Accent else Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            item {
                Panel("Dashboard overview") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(
                            label = "Total contacts",
                            value = if (sheetConnected) contacts.size.toString() else "—",
                            symbol = "👥",
                            modifier = Modifier.weight(1f)
                        )

                        StatCard(
                            label = "Pending",
                            value = if (sheetConnected) eligible.size.toString() else "—",
                            symbol = "◷",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatCard(
                            label = "Attempts recorded",
                            value = if (sheetConnected) {
                                contacts.count {
                                    it.status.equals(
                                        "Attempted - outcome unverified",
                                        ignoreCase = true
                                    ) ||
                                            it.status.equals("Called", ignoreCase = true) ||
                                            it.status.equals("Completed", ignoreCase = true) ||
                                            it.status.equals("Done", ignoreCase = true)
                                }.toString()
                            } else {
                                "—"
                            },
                            symbol = "↗",
                            modifier = Modifier.weight(1f)
                        )

                        StatCard(
                            label = "Need review",
                            value = if (sheetConnected) {
                                contacts.count {
                                    it.status.contains("unverified", ignoreCase = true) ||
                                            it.status.contains("review", ignoreCase = true)
                                }.toString()
                            } else {
                                "—"
                            },
                            symbol = "!",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                val attemptedCount = contacts.count {
                    it.status.equals("Attempted - outcome unverified", ignoreCase = true) ||
                            it.status.equals("Called", ignoreCase = true) ||
                            it.status.equals("Completed", ignoreCase = true) ||
                            it.status.equals("Done", ignoreCase = true)
                }
                val completedCount = contacts.count {
                    it.status.equals("Completed", ignoreCase = true) ||
                            it.status.equals("Done", ignoreCase = true)
                }
                val reviewCount = contacts.count {
                    it.status.contains("unverified", ignoreCase = true) ||
                            it.status.contains("review", ignoreCase = true)
                }
                val pendingCount = eligible.size
                val totalCount = contacts.size
                val progress = if (totalCount > 0) {
                    completedCount.toFloat() / totalCount.toFloat()
                } else {
                    0f
                }

                Panel("Call analytics") {
                    Text(
                        if (sheetConnected) "Summary based on the contacts currently loaded from Google Sheets."
                        else "Connect Google Sheets to view analytics.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AnalyticsMetric(
                            label = "Completed",
                            value = if (sheetConnected) completedCount.toString() else "—",
                            modifier = Modifier.weight(1f),
                            valueColor = Accent
                        )
                        AnalyticsMetric(
                            label = "Attempted",
                            value = if (sheetConnected) attemptedCount.toString() else "—",
                            modifier = Modifier.weight(1f),
                            valueColor = Purple
                        )
                        AnalyticsMetric(
                            label = "Review",
                            value = if (sheetConnected) reviewCount.toString() else "—",
                            modifier = Modifier.weight(1f),
                            valueColor = Warning
                        )
                    }

                    Text(
                        "Completion progress",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    LinearProgressIndicator(
                        progress = { if (sheetConnected) progress else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Accent,
                        trackColor = DisabledColor
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (sheetConnected) "$completedCount of $totalCount contacts completed" else "No data yet",
                            color = Muted,
                            fontSize = 12.sp
                        )
                        Text(
                            if (sheetConnected && totalCount > 0) "${(progress * 100).toInt()}%" else "—",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Divider(color = BorderColor)

                    AnalyticsBarRow(
                        label = "Pending",
                        count = if (sheetConnected) pendingCount else 0,
                        total = if (sheetConnected) totalCount else 0,
                        barColor = Warning
                    )
                    AnalyticsBarRow(
                        label = "Attempted",
                        count = if (sheetConnected) attemptedCount else 0,
                        total = if (sheetConnected) totalCount else 0,
                        barColor = Purple
                    )
                    AnalyticsBarRow(
                        label = "Needs review",
                        count = if (sheetConnected) reviewCount else 0,
                        total = if (sheetConnected) totalCount else 0,
                        barColor = Warning
                    )
                }
            }

            item {
                Panel("Call history") {
                    Text(
                        "Saved on this device. Up to $MAX_CALL_HISTORY recent call attempts are kept.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    if (callHistory.isEmpty()) {
                        Text(
                            "No call history yet. Call attempts will appear here.",
                            color = Muted,
                            fontSize = 14.sp
                        )
                    } else {
                        callHistory.take(10).forEach { entry ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = InputColor,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderColor)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        entry.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(entry.phone, color = Muted, fontSize = 13.sp)
                                    Text(entry.dateTime, color = Muted, fontSize = 12.sp)
                                    Text(
                                        entry.outcome,
                                        color = if (entry.outcome.contains("unverified", true)) Warning else Accent,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        if (callHistory.size > 10) {
                            Text(
                                "Showing 10 most recent of ${callHistory.size} saved entries.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = onClearCallHistory,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)
                        ) {
                            Text("Clear call history")
                        }
                    }
                }
            }

            item {
                Panel("Google Sheets") {
                    Text(
                        connectionMessage,
                        color = if (sheetConnected) Accent else Muted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Button(
                        onClick = onConnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        enabled = !isLoading &&
                                state !in listOf(
                            QueueState.CALLING,
                            QueueState.BREAK
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = Color.White,
                            disabledContainerColor = DisabledColor,
                            disabledContentColor = Muted
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }

                        Text(
                            if (isLoading) "Loading..."
                            else "Connect / Refresh Google Sheets",
                            fontSize = 14.sp
                        )
                    }

                    Text(
                        "Read/write access requested. Detected call ends are saved as " +
                                "\"Attempted - outcome unverified\"; answer/failure cannot be confirmed reliably.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            item {
                Panel("Calling queue settings") {
                    OutlinedTextField(
                        value = breakDuration,
                        onValueChange = {
                            if (it.isEmpty() || it.all(Char::isDigit)) {
                                breakDuration = it
                            }
                        },
                        label = { Text("Break duration (seconds)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state in listOf(
                            QueueState.STOPPED,
                            QueueState.COMPLETED
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Muted,
                            focusedContainerColor = InputColor,
                            unfocusedContainerColor = InputColor,
                            disabledContainerColor = DisabledColor,
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = BorderColor,
                            disabledBorderColor = BorderColor,
                            focusedLabelColor = Accent,
                            unfocusedLabelColor = Muted,
                            disabledLabelColor = Muted,
                            cursorColor = Accent
                        )
                    )

                    Text(
                        "Default: 180 seconds (3 minutes). Range: 0–86400.",
                        color = Muted,
                        fontSize = 12.sp
                    )

                    if (!breakValid) {
                        Text(
                            "Enter a whole number from 0 to 86400.",
                            color = Danger
                        )
                    }
                }
            }

            item {
                Panel("Queue controls") {
                    Text(
                        "Status: $stateText",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(statusMessage, color = Muted)

                    if (
                        current != null &&
                        state in listOf(
                            QueueState.CALLING,
                            QueueState.BREAK,
                            QueueState.PAUSED,
                            QueueState.WAITING
                        )
                    ) {
                        Text(
                            "Current contact: " +
                                    current.name.ifBlank { "Unnamed contact" },
                            color = Color.White
                        )
                        Text("Phone: ${current.phone}", color = Muted)
                    }

                    if (state == QueueState.BREAK) {
                        Text(
                            "Next call in: ${duration(seconds)}",
                            color = Warning,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!callPermissionsGranted) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Muted
                            ),
                            border = BorderStroke(1.dp, BorderColor)
                        ) {
                            Text("Grant calling permissions")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { beginQueue() },
                            modifier = Modifier.weight(1f),
                            enabled = sheetConnected &&
                                    state in listOf(
                                QueueState.STOPPED,
                                QueueState.COMPLETED
                            ) && breakValid,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Purple,
                                contentColor = Color.White,
                                disabledContainerColor = DisabledColor,
                                disabledContentColor = Muted
                            )
                        ) {
                            Text("Start")
                        }

                        OutlinedButton(
                            onClick = {
                                if (state == QueueState.PAUSED) {
                                    state = pausedFrom
                                    statusMessage = "Resumed."
                                } else {
                                    pausedFrom = state
                                    state = QueueState.PAUSED
                                    statusMessage =
                                        "Paused. An active call is not hung up automatically."
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = state in listOf(
                                QueueState.CALLING,
                                QueueState.BREAK,
                                QueueState.PAUSED
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White,
                                disabledContentColor = Muted
                            ),
                            border = BorderStroke(1.dp, BorderColor)
                        ) {
                            Text(
                                if (state == QueueState.PAUSED) "Resume"
                                else "Pause"
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { stopQueue() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state != QueueState.STOPPED,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Muted
                        ),
                        border = BorderStroke(1.dp, BorderColor)
                    ) {
                        Text("Stop queue")
                    }

                    if (state == QueueState.WAITING) {
                        Text(
                            "A call start could not be verified or the contact needs review. Confirm the phone state before resuming.",
                            color = Warning
                        )

                        Button(
                            onClick = { reviewCall(true) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Purple,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Confirm ended — completed")
                        }

                        OutlinedButton(
                            onClick = { reviewCall(false) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, BorderColor)
                        ) {
                            Text("Confirm ended — failed")
                        }

                        OutlinedButton(
                            onClick = { stopQueue() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, BorderColor)
                        ) {
                            Text("Stop instead")
                        }
                    }

                    Text(
                        "Spreadsheet row order is used; Call Date and Call Time are ignored.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }
            item {
                Panel("Contacts from Google Sheets") {
                    Text(
                        if (!sheetConnected) {
                            "Connect Google Sheets to load contacts."
                        } else {
                            "${contacts.size} contact(s) loaded"
                        },
                        color = if (sheetConnected) Accent else Muted
                    )
                }
            }

            if (sheetConnected) {
                itemsIndexed(contacts) { index, contact ->
                    Panel(
                        "Contact ${index + 1}: " +
                                contact.name.ifBlank { "Unnamed contact" }
                    ) {
                        Detail("Name", contact.name)
                        Detail("Phone", contact.phone)
                        Detail("Call date", contact.callDate)
                        Detail("Call time", contact.callTime)
                        Detail(
                            "Status",
                            contact.status.ifBlank { "Pending" }
                        )

                        if (contact.phone.isBlank()) {
                            Text(
                                "Skipped: phone number is missing.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            item {
                Panel("Important limitations") {
                    Text(
                        "• Only call people you are authorized to contact.\n" +
                                "• Keep the app in the foreground; Android may restrict automatic dialing.\n" +
                                "• Call-state events cannot reliably prove whether a call was answered, rejected, or failed.\n" +
                                "• A detected call end is recorded as Attempted - outcome unverified, then the queue waits for the configured break.\n" +
                                "• A call that never becomes verifiably active requires manual review.",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun Panel(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                content()
            }
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    symbol: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(124.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF263548)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                symbol,
                color = Accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                value,
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                label,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun AnalyticsMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = InputColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                value,
                color = valueColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun AnalyticsBarRow(
    label: String,
    count: Int,
    total: Int,
    barColor: Color
) {
    val fraction = if (total > 0) (count.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(count.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = barColor,
            trackColor = DisabledColor
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            color = Muted,
            modifier = Modifier.weight(1f)
        )

        Text(
            value.ifBlank { "Not provided" },
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Summary(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Muted)

        Text(
            value,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun duration(total: Int): String {
    return "%02d:%02d".format(total / 60, total % 60)
}
