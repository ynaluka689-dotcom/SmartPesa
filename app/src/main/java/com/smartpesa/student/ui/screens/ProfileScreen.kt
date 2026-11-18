package com.smartpesa.student.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartpesa.student.data.FinanceViewModel
import com.smartpesa.student.data.Transaction
import com.smartpesa.student.ui.components.AppIcons
import com.smartpesa.student.ui.components.Caption
import com.smartpesa.student.ui.components.HideDialogNavigationKeys
import com.smartpesa.student.ui.components.Panel
import com.smartpesa.student.ui.theme.Amber
import com.smartpesa.student.ui.theme.Blue
import com.smartpesa.student.ui.theme.CardColor
import com.smartpesa.student.ui.theme.Green
import com.smartpesa.student.ui.theme.Muted
import com.smartpesa.student.ui.theme.Pink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export through Android's document picker, so the student chooses where the CSV is saved. */
@Composable
fun ProfileScreen(model: FinanceViewModel, transactions: List<Transaction>) {
    var name by remember { mutableStateOf(model.initialName) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(name) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmErase by rememberSaveable { mutableStateOf(false) }
    val requestKey = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val operation by model.utilityOperation.collectAsStateWithLifecycle()
    val busy = operation?.busy == true
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("settings", 0) }
    var profileImageUri by remember {
        mutableStateOf(
            preferences.getString("profile_image_uri", null)?.let {
                Uri.parse(it)
            }
        )
    }

    val profileImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            profileImageUri = uri

            preferences.edit()
                .putString("profile_image_uri", uri.toString())
                .apply()
        }
    }

    val profileBitmap = remember(profileImageUri) {
        profileImageUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
    }
    var messages by remember { mutableStateOf(preferences.getBoolean("messages", false)) }
    var receipts by remember { mutableStateOf(preferences.getBoolean("receipts", true)) }
    var warnings by remember { mutableStateOf(preferences.getBoolean("budget_alerts", true)) }
    LaunchedEffect(operation, requestKey) {
        if (operation?.key == requestKey && !busy) {
            messages = preferences.getBoolean("messages", false)
            if (operation?.succeeded == true) when (operation?.kind) {
                "erase" -> { confirmErase = false; name = "Student" }
                "import" -> { settingsDialog = null; info = "Added ${operation?.count ?: 0} new payments. Messages already added were skipped." }
                "access" -> if (messages) Toast.makeText(context, "Adding supported M-PESA messages from the last 90 days.", Toast.LENGTH_LONG).show()
            }
            else if (operation?.failed == true) { settingsDialog = null; info = "Could not finish. Please try again." }
            model.clearUtility(requestKey)
        }
    }
    val notificationAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        Toast.makeText(context, if (allowed) "Notifications enabled" else "You can keep tracking without notifications.", Toast.LENGTH_LONG).show()
    }
    val messageAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        messages = listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS).all { result[it] == true || context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        model.setMessageAccess(messages, requestKey)
        if (!messages) Toast.makeText(context, "Message access was not enabled. Manual tracking is still available.", Toast.LENGTH_LONG).show()
    }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                        model.export(out)
                    } ?: error("Unable to open file")
                }.isSuccess
            }
            Toast.makeText(context, if (success) "Transactions exported" else "Could not export. Try another location.", Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("Profile", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp))
        {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Blue.copy(alpha = 0.12f))
                    .clickable {
                        profileImagePicker.launch(arrayOf("image/*"))
                    },
                contentAlignment = Alignment.Center
            ) {

                if (profileBitmap != null) {

                    Image(
                        bitmap = profileBitmap.asImageBitmap(),
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                } else {

                    Text(
                        name.take(1).uppercase(),
                        color = Blue,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Student · Local profile", color = Muted, fontSize = 12.sp)
            Text(
                "Tap photo to change profile picture",
                color = Blue,
                fontSize = 11.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f)) { Caption("TRANSACTIONS"); Text(transactions.size.toString(), fontWeight = FontWeight.Bold) }
            Panel(Modifier.weight(1f)) { Caption("STORAGE"); Text("On this device", color = Blue, fontSize = 12.sp) }
        }
        Caption("SETTINGS")
        Panel {
            ProfileAction("Personal Info", AppIcons.Person, Blue) { draft = name; edit = true }
            HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            ProfileAction("Export transactions", AppIcons.FileDownload, Amber) { export.launch("SmartPesa-transactions.csv") }
            HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            ProfileAction("Notifications", AppIcons.Info, Blue) { settingsDialog = "notifications" }
            HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            ProfileAction("Message access", AppIcons.ReceiptLong, Blue) { settingsDialog = "messages" }
            HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            ProfileAction("Privacy & Storage", AppIcons.Shield, Green) {
                info = "Your ledger is encrypted and stays on this device. Message access is optional. Exported CSV files are readable outside this app, so choose a private location. Android stores your original messages separately."
            }
            HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            ProfileAction("Delete all app data", AppIcons.Shield, Pink) { confirmErase = true }
            HorizontalDivider(color = Muted.copy(alpha = 0.1f))
            ProfileAction("About SmartPesa", AppIcons.Info, Blue) {
                info = "Smart Expense Tracking System for University Students. Track your income, expenses and monthly spending limits. Version 0.3.0. Academic project by Luka Mwango YNA, Kiriri Women's University of Science and Technology."
            }
        }
    }
    if (confirmErase) AlertDialog(onDismissRequest = { if (!busy) confirmErase = false }, title = { HideDialogNavigationKeys(); Text("Delete all app data?") },
        text = { Text("This deletes your ledger, limits, remembered categories, notification history and profile name. Message access will be turned off. Files you exported and messages in your phone's inbox remain.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { model.erase(requestKey) }) { Text(if (busy) "Deleting…" else "Delete all", color = Pink) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmErase = false }) { Text("Cancel") } })
    settingsDialog?.let { setting -> AlertDialog(onDismissRequest = { settingsDialog = null }, title = { HideDialogNavigationKeys(); Text(if (setting == "messages") "Message access" else "Notifications") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (setting == "messages") {
                Text("Optionally add supported M-PESA payments from the last 90 days and new messages. Other messages are ignored. Your messages stay on this phone. Unsupported formats, cash withdrawals and reversals need manual checking.")
                Text(if (messages) "Message access is on" else "Message access is off", color = Blue)
                Button(enabled = !busy, onClick = {
                    if (messages) { model.setMessageAccess(false, requestKey) }
                    else messageAccess.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                }) { Text(if (messages) "Turn off" else "Enable message access") }
                if (messages) TextButton(enabled = !busy, onClick = { model.importMessages(requestKey) }) { Text(if (busy) "Importing…" else "Import recent messages") }
            } else {
                Text("Get transaction updates and warnings as you approach your monthly spending limits. Alerts keep amounts and merchant details private.")
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Transaction updates", Modifier.weight(1f)); Switch(receipts, { receipts = it; preferences.edit().putBoolean("receipts", it).apply() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Spending warnings", Modifier.weight(1f)); Switch(warnings, { warnings = it; preferences.edit().putBoolean("budget_alerts", it).apply() }) }
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationAccess.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Allow notifications") }
            }
        } }, confirmButton = { TextButton(onClick = { settingsDialog = null }) { Text("Close") } }) }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { HideDialogNavigationKeys(); Text("Personal Info") },
        text = { OutlinedTextField(draft, { draft = it }, label = { Text("Your name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { name = draft.trim(); model.saveName(name); edit = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
    info?.let { message -> AlertDialog(onDismissRequest = { info = null }, title = { HideDialogNavigationKeys(); Text("SmartPesa") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } }) }
}

@Composable
private fun ProfileAction(title: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = CardColor) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Text(title, Modifier.weight(1f), fontSize = 14.sp)
            Icon(AppIcons.ChevronRight, null, tint = Muted)
        }
    }
}
