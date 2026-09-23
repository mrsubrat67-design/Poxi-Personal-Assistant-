package com.example.poxi.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.poxi.permission.PermissionValidationLayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoxiScreen(
    viewModel: PoxiViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var showKeyDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var showKeyboardInput by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity

    // Multi-permission launcher for Microphone and Contacts
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val mic = permissions[Manifest.permission.RECORD_AUDIO] == true
        val contacts = permissions[Manifest.permission.READ_CONTACTS] == true
        val permanentlyDenied = if (!mic && activity != null) {
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        } else {
            false
        }
        viewModel.onPermissionsResult(mic, contacts, permanentlyDenied)
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    // Scroll to bottom on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        containerColor = Color(0xFF090D16)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF8B5CF6), Color(0xFF06B6D4))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Poxi",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Android Bridge",
                                    color = Color(0xFF34D399),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = uiState.currentLanguage,
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.testTag("info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Test Specs",
                            tint = Color(0xFF94A3B8)
                        )
                    }

                    IconButton(
                        onClick = { showKeyDialog = true },
                        modifier = Modifier.testTag("key_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "API Key",
                            tint = if (uiState.apiKey.isNotBlank()) Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.clearMessages() },
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear History",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Robust Permissions Validation Banner if RECORD_AUDIO not granted
            AnimatedVisibility(
                visible = !uiState.hasMicrophonePermission,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = Color(0xFF2B1425),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFE11D48).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("mic_permission_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MicOff,
                                contentDescription = "Microphone Denied",
                                tint = Color(0xFFFB7185),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Microphone permission required for Gemini voice session",
                                color = Color(0xFFFDE8E8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = {
                                if (uiState.isPermissionPermanentlyDenied) {
                                    viewModel.openAppSettings()
                                } else {
                                    val permissions = mutableListOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.CALL_PHONE
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permissionsLauncher.launch(permissions.toTypedArray())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("banner_enable_mic_button")
                        ) {
                            Text(
                                text = if (uiState.isPermissionPermanentlyDenied) "Settings" else "Allow",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Compact Visualizer & Status Center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PoxiVisualizer(
                        isListening = uiState.isListening,
                        isSpeaking = uiState.isSpeaking,
                        isProcessing = uiState.isProcessing,
                        amplitude = uiState.audioAmplitude,
                        size = 100.dp,
                        modifier = Modifier.testTag("poxi_visualizer")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = uiState.statusMessage,
                        color = when {
                            uiState.isListening -> Color(0xFF06B6D4)
                            uiState.isSpeaking -> Color(0xFFA855F7)
                            uiState.isProcessing -> Color(0xFFEC4899)
                            else -> Color(0xFF94A3B8)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Contact Disambiguation Card (Requirement 6: Multiple Matches)
            uiState.pendingContactDisambiguation?.let { disambiguationContacts ->
                ContactDisambiguationCard(
                    contacts = disambiguationContacts,
                    onContactSelected = { viewModel.selectDisambiguatedContact(it) },
                    onDismiss = { viewModel.dismissDisambiguation() }
                )
            }

            // Conversation Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("conversation_list"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }

            // Quick Voice Suggestion Chips
            QuickSuggestionChips(
                onChipClick = { suggestion ->
                    viewModel.processUserInput(suggestion)
                }
            )

            // Keyboard input row (expandable)
            AnimatedVisibility(visible = showKeyboardInput) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Type voice command or prompt...", color = Color(0xFF64748B)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("text_input_field"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledIconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                viewModel.processUserInput(textInput)
                                textInput = ""
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF8B5CF6)),
                        modifier = Modifier.testTag("send_button")
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                    }
                }
            }

            // Bottom Voice Control Deck
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keyboard toggle button
                IconButton(
                    onClick = { showKeyboardInput = !showKeyboardInput },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showKeyboardInput) Color(0xFF334155) else Color(0xFF1E293B)
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .testTag("toggle_keyboard_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Toggle Keyboard",
                        tint = Color.White
                    )
                }

                // Central Main Microphone Pulsing Button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            if (uiState.isListening) {
                                Brush.radialGradient(listOf(Color(0xFF22D3EE), Color(0xFF0891B2)))
                            } else {
                                Brush.radialGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9)))
                            }
                        )
                        .clickable {
                            val micGranted = PermissionValidationLayer.hasRecordAudioPermission(context)
                            if (!micGranted) {
                                val isPermanentlyDenied = if (activity != null) {
                                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                                } else {
                                    false
                                }
                                viewModel.onPermissionsResult(
                                    micGranted = false,
                                    contactsGranted = uiState.hasContactsPermission,
                                    permanentlyDenied = isPermanentlyDenied
                                )

                                if (!isPermanentlyDenied) {
                                    val permissions = mutableListOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.CALL_PHONE
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permissionsLauncher.launch(permissions.toTypedArray())
                                }
                            } else {
                                viewModel.toggleListening()
                            }
                        }
                        .testTag("mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (uiState.isListening) "Stop Listening" else "Start Speaking",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Test Case 10: Interruption Button (stops Poxi's speech immediately)
                IconButton(
                    onClick = { viewModel.interruptSpeaking() },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (uiState.isSpeaking) Color(0xFFEF4444) else Color(0xFF1E293B)
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .testTag("interrupt_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Interrupt Poxi",
                        tint = if (uiState.isSpeaking) Color.White else Color(0xFF64748B)
                    )
                }
            }
        }
    }

    // API Key Dialog
    if (showKeyDialog) {
        var tempKey by remember { mutableStateOf(uiState.apiKey) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Gemini Live API Key") },
            text = {
                Column {
                    Text(
                        "Configured via AI Studio secrets or entered directly. If left blank, Poxi uses the built-in native NLU action engine!",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateApiKey(tempKey)
                        showKeyDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Test Cases & Spec Info Dialog
    if (showInfoDialog) {
        val testCases = listOf(
            "1. 'Hello Poxi.'" to "Poxi responds with audible voice.",
            "2. 'Hindi mein baat karo.'" to "Switches to Hindi response.",
            "3. 'Talk to me in English.'" to "Switches to English response.",
            "4. 'Hinglish mein baat karo.'" to "Responds in conversational Hinglish.",
            "5. 'WhatsApp kholo.'" to "Executes WhatsApp launch action.",
            "6. 'Open WhatsApp.'" to "Executes WhatsApp launch action.",
            "7. 'Mummy ko call karo.'" to "Searches contacts, dials match.",
            "8. 'Call Rahul.'" to "Finds contact; prompts if multiple.",
            "9. 'Call 9876543210.'" to "Opens phone dialer with number.",
            "10. Interrupt speaking" to "Immediate voice stop on interrupt button."
        )

        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Specification Test Cases") },
            text = {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    item {
                        Text(
                            "Tap any test case below to execute it immediately:",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(testCases) { (input, expected) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    val cleaned = input.substringAfter(". ").replace("'", "")
                                    viewModel.processUserInput(cleaned)
                                    showInfoDialog = false
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(text = input, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8), fontSize = 13.sp)
                                Text(text = expected, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showInfoDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Clear UI Prompt when RECORD_AUDIO Permission is Denied
    if (uiState.showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionDialog() },
            icon = {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3F1B2A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MicOff,
                        contentDescription = "Microphone Disabled",
                        tint = Color(0xFFFB7185),
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    text = uiState.permissionDialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = uiState.permissionDialogMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE2E8F0)
                    )
                    if (uiState.isPermissionPermanentlyDenied) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Microphone permission has been disabled. Tap 'Open Settings' below to enable it under App Permissions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "Poxi uses your microphone to transcribe and converse via Gemini in real-time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (uiState.isPermissionPermanentlyDenied) {
                            viewModel.openAppSettings()
                        } else {
                            viewModel.dismissPermissionDialog()
                            val permissions = mutableListOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionsLauncher.launch(permissions.toTypedArray())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B5CF6)
                    ),
                    modifier = Modifier.testTag("grant_permission_button")
                ) {
                    Text(if (uiState.isPermissionPermanentlyDenied) "Open Settings" else "Grant Permission")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissPermissionDialog() },
                    modifier = Modifier.testTag("dismiss_permission_button")
                ) {
                    Text("Not Now", color = Color(0xFFCBD5E1))
                }
            },
            containerColor = Color(0xFF131B2E),
            modifier = Modifier.testTag("permission_denied_dialog")
        )
    }
}
