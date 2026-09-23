package com.example.poxi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.poxi.model.ChatMessage
import com.example.poxi.model.ContactItem
import com.example.poxi.model.MessageRole
import com.example.poxi.model.ToolActionInfo

@Composable
fun MessageBubble(
    message: ChatMessage,
    onContactSelected: ((ContactItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8B5CF6)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Poxi Assistant",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) Color(0xFF6366F1) else Color(0xFF1E293B),
                tonalElevation = 2.dp,
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    )

                    if (!message.detectedLanguage.isNullOrBlank() && !isUser) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = message.detectedLanguage,
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // If message executed a tool action, display Action Badge Card
            if (message.toolAction != null) {
                Spacer(modifier = Modifier.height(6.dp))
                ActionCard(action = message.toolAction)
            }
        }
    }
}

@Composable
fun ActionCard(
    action: ToolActionInfo,
    modifier: Modifier = Modifier
) {
    val (icon, tintColor, title) = when (action.functionName) {
        "openWhatsApp" -> Triple(Icons.AutoMirrored.Outlined.Chat, Color(0xFF22C55E), "WhatsApp Action")
        "openApp" -> Triple(Icons.AutoMirrored.Filled.OpenInNew, Color(0xFF38BDF8), "App Action")
        "makeCall" -> Triple(Icons.Default.Call, Color(0xFF10B981), "Phone Call")
        "callContact" -> Triple(Icons.Default.Phone, Color(0xFFA855F7), "Contact Call")
        "openUrl" -> Triple(Icons.Default.OpenInBrowser, Color(0xFFF59E0B), "Web Link")
        else -> Triple(Icons.Default.PlayArrow, Color(0xFF6366F1), "Device Action")
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("action_card_${action.functionName}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tintColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tintColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (action.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (action.success) Color(0xFF22C55E) else Color(0xFFEF4444),
                        modifier = Modifier.size(14.dp)
                    )
                }

                Text(
                    text = action.summary,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun ContactDisambiguationCard(
    contacts: List<ContactItem>,
    onContactSelected: (ContactItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("disambiguation_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color(0xFFA78BFA),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Multiple Contacts Found. Who to call?",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            contacts.forEach { contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF312E81))
                        .clickable { onContactSelected(contact) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = contact.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${contact.typeLabel}: ${contact.phoneNumber}",
                            color = Color(0xFFA5B4FC),
                            fontSize = 12.sp
                        )
                    }

                    FilledTonalButton(
                        onClick = { onContactSelected(contact) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF4F46E5),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.testTag("call_contact_${contact.id}")
                    ) {
                        Icon(imageVector = Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Call", fontSize = 12.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = Color(0xFFA5B4FC))
                }
            }
        }
    }
}

@Composable
fun QuickSuggestionChips(
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = listOf(
        "WhatsApp kholo" to "💬",
        "Call Mom" to "📞",
        "Open YouTube" to "🎬",
        "Call 9876543210" to "📱",
        "Open Settings" to "⚙️",
        "Mummy ko call karo" to "👵",
        "Hinglish mein baat karo" to "🇮🇳",
        "Talk in English" to "🇬🇧",
        "Hindi mein baat karo" to "🗣️"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { (text, emoji) ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E293B),
                tonalElevation = 1.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onChipClick(text) }
                    .testTag("suggestion_chip_$text")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = emoji, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = text,
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
