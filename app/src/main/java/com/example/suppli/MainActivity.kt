package com.example.suppli

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.UUID
import kotlin.math.roundToInt

private val FallbackLight = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
    secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
    tertiary = androidx.compose.ui.graphics.Color(0xFF7D5260)
)

private val FallbackDark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF),
    secondary = androidx.compose.ui.graphics.Color(0xFFCCC2DC),
    tertiary = androidx.compose.ui.graphics.Color(0xFFEFB8C8)
)

data class Supplement(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String,
    val doseValue: String,
    val doseUnit: String,
    val targetDoses: Int = 1,
    val takenDoses: Int = 0,
    val timeOfDay: DayPartition = DayPartition.MORNING,
    val reminderHour: Int = if (timeOfDay == DayPartition.MORNING) 8 else 20,
    val reminderMinute: Int = 0,
    val reminderEnabled: Boolean = true
) {
    val isComplete: Boolean get() = takenDoses >= targetDoses
    val reminderLabel: String get() = "%02d:%02d".format(reminderHour, reminderMinute)
}

enum class DayPartition { MORNING, EVENING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createChannel(this)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(this)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(this)
                    dark -> FallbackDark
                    else -> FallbackLight
                },
                shapes = Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(20.dp),
                    large = RoundedCornerShape(28.dp)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SupplementTrackerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementTrackerApp() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        NotificationHelper.createChannel(context)
        NotificationHelper.scheduleAll(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun commit(updated: List<Supplement>) {
        supplements = updated
        SupplementStore.save(context, updated)
        NotificationHelper.scheduleAll(context)
    }

    fun increment(s: Supplement) {
        val updated = supplements.map { if (it.id == s.id && !it.isComplete) it.copy(takenDoses = it.takenDoses + 1) else it }
        commit(updated)
    }

    val allTaken = supplements.isNotEmpty() && supplements.all { it.isComplete }
    val permissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HeaderBanner(
                allPillsTakenToday = allTaken,
                notificationsEnabled = permissionGranted,
                onNotificationsClick = { showSettings = true }
            )
            WeeklyOverviewTracker(allPillsTakenToday = allTaken)

            if (!permissionGranted) {
                NotificationPermissionCard(onEnable = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else showSettings = true
                })
            }

            if (supplements.isEmpty()) {
                EmptyStateView(onAdd = { showAddSheet = true })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item { CategoryHeaderChip("Morning", MaterialTheme.colorScheme.tertiary, "Morning reminders") }
                    val morning = supplements.filter { it.timeOfDay == DayPartition.MORNING }
                    if (morning.isEmpty()) item { DropZonePlaceholder("No morning supplements") }
                    else items(morning, key = { it.id }) { s ->
                        DraggableSupplementCard(s, { increment(s) }, { supplementToEdit = it }) { target ->
                            commit(supplements.map { if (it.id == s.id) it.copy(timeOfDay = target) else it })
                        }
                    }

                    item { CategoryHeaderChip("Evening", MaterialTheme.colorScheme.primary, "Evening reminders") }
                    val evening = supplements.filter { it.timeOfDay == DayPartition.EVENING }
                    if (evening.isEmpty()) item { DropZonePlaceholder("No evening supplements") }
                    else items(evening, key = { it.id }) { s ->
                        DraggableSupplementCard(s, { increment(s) }, { supplementToEdit = it }) { target ->
                            commit(supplements.map { if (it.id == s.id) it.copy(timeOfDay = target) else it })
                        }
                    }
                }
            }
        }

        FloatingControlHub(onClick = { showAddSheet = true }, modifier = Modifier.align(Alignment.BottomCenter))

        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                AddSupplementSheet { newSup ->
                    commit(supplements + newSup)
                    showAddSheet = false
                }
            }
        }

        supplementToEdit?.let { edit ->
            EditSupplementDialog(
                supplement = edit,
                onDismiss = { supplementToEdit = null },
                onSave = { updated ->
                    commit(supplements.map { if (it.id == updated.id) updated else it })
                    supplementToEdit = null
                },
                onDeleteTrigger = {
                    supplementToDelete = edit
                    supplementToEdit = null
                }
            )
        }

        supplementToDelete?.let { doomed ->
            AlertDialog(
                onDismissRequest = { supplementToDelete = null },
                icon = { Icon(Icons.Default.Settings, null) },
                title = { Text("Delete ${doomed.name}?") },
                text = { Text("This removes the supplement and its daily reminder.") },
                confirmButton = {
                    Button(onClick = {
                        NotificationHelper.cancel(context, doomed)
                        commit(supplements.filterNot { it.id == doomed.id })
                        supplementToDelete = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { supplementToDelete = null }) { Text("Cancel") } }
            )
        }

        if (showSettings) {
            NotificationSettingsDialog(
                onDismiss = { showSettings = false },
                notificationsGranted = permissionGranted,
                onRequestPermission = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onOpenExactAlarmSettings = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = android.net.Uri.parse("package:${context.packageName}") })
                    }
                }
            )
        }
    }
}

@Composable
fun HeaderBanner(allPillsTakenToday: Boolean, notificationsEnabled: Boolean, onNotificationsClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Daily Stack", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                if (allPillsTakenToday) "Everything taken today" else "Tap a supplement when you take it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onNotificationsClick) {
            Icon(
                if (notificationsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                contentDescription = "Notification settings"
            )
        }
    }
}

@Composable
fun NotificationPermissionCard(onEnable: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Alarm, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Turn on reminders", fontWeight = FontWeight.Bold)
                Text("Get a notification when it is time for a supplement.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onEnable) { Text("Enable") }
        }
    }
}

@Composable
fun CategoryHeaderChip(title: String, color: androidx.compose.ui.graphics.Color, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = color)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DraggableSupplementCard(supplement: Supplement, onIncrement: () -> Unit, onLongClick: (Supplement) -> Unit, onDraggedTo: (DayPartition) -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (dragging) 1.04f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "dragScale")

    Box(
        Modifier.fillMaxWidth().offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(supplement.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        if (offsetY < -140f && supplement.timeOfDay == DayPartition.EVENING) onDraggedTo(DayPartition.MORNING)
                        if (offsetY > 140f && supplement.timeOfDay == DayPartition.MORNING) onDraggedTo(DayPartition.EVENING)
                        offsetX = 0f; offsetY = 0f
                    },
                    onDragCancel = { dragging = false; offsetX = 0f; offsetY = 0f },
                    onDrag = { change, amount -> change.consume(); offsetX += amount.x; offsetY += amount.y }
                )
            }.scale(scale)
    ) {
        GlassSupplementItem(supplement, onIncrement) { onLongClick(supplement) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassSupplementItem(supplement: Supplement, onIncrement: () -> Unit, onLongClick: () -> Unit) {
    val progress = (supplement.takenDoses.toFloat() / supplement.targetDoses.toFloat()).coerceIn(0f, 1f)
    var squish by remember { mutableStateOf(1f) }
    val animatedSquish by animateFloatAsState(squish, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "squish")

    Card(
        modifier = Modifier.fillMaxWidth().scale(animatedSquish).combinedClickable(onClick = { squish = .96f; onIncrement() }, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = if (supplement.isComplete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(60.dp)) {
                    drawCircle(MaterialTheme.colorScheme.outlineVariant, style = Stroke(4.dp.toPx()))
                    drawArc(
                        Brush.sweepGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)),
                        -90f, progress * 360f, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Text(supplement.emoji, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(supplement.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textDecoration = if (supplement.isComplete) TextDecoration.LineThrough else null)
                Text(
                    if (supplement.doseUnit == "pills") "${supplement.takenDoses}/${supplement.targetDoses} pills taken" else "${supplement.doseValue} ${supplement.doseUnit}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Alarm, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(supplement.reminderLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (!supplement.reminderEnabled) {
                        Spacer(Modifier.width(8.dp))
                        Text("Reminders off", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            AnimatedVisibility(supplement.isComplete, enter = scaleIn() + fadeIn()) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
fun WeeklyOverviewTracker(allPillsTakenToday: Boolean) {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 6 else it - 2 }
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEachIndexed { i, d ->
                val isToday = i == today
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { Text(d, color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun FloatingControlHub(onClick: () -> Unit, modifier: Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.navigationBarsPadding().padding(bottom = 20.dp),
        icon = { Icon(Icons.Default.Add, null) },
        text = { Text("Add supplement", fontWeight = FontWeight.Bold) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSupplementSheet(onAdd: (Supplement) -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mg") }
    var target by remember { mutableStateOf("1") }
    var emoji by remember { mutableStateOf("💊") }
    var timing by remember { mutableStateOf(DayPartition.MORNING) }
    var reminderEnabled by remember { mutableStateOf(true) }
    var showTimePicker by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(initialHour = if (timing == DayPartition.MORNING) 8 else 20, initialMinute = 0, is24Hour = true)
    val focus = LocalFocusManager.current
    val units = listOf("mg", "pills", "IU", "g", "mcg")
    val emojis = listOf("💊", "🌿", "☀️", "🐟", "⚡", "🦠", "💪", "🧠", "🌙", "💧", "🧪", "🧡")

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Add supplement", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Set the dose and an optional daily reminder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(emojis) { e -> FilterChip(selected = emoji == e, onClick = { emoji = e }, label = { Text(e) }) }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Supplement name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text("Dose") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded, { expanded = !expanded }, Modifier.weight(1f)) {
                OutlinedTextField(unit, {}, Modifier.menuAnchor(), readOnly = true, label = { Text("Unit") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, singleLine = true)
                ExposedDropdownMenu(expanded, { expanded = false }) { units.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; expanded = false }) } }
            }
        }
        if (unit == "pills") {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(target, { target = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Pills per day") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(timing == DayPartition.MORNING, { timing = DayPartition.MORNING }, label = { Text("Morning") })
            FilterChip(timing == DayPartition.EVENING, { timing = DayPartition.EVENING }, label = { Text("Evening") })
        }
        Spacer(Modifier.height(10.dp))
        ListItem(
            leadingContent = { Icon(Icons.Default.Alarm, null) },
            headlineContent = { Text("Daily reminder") },
            supportingContent = { Text("${timeState.hour.toString().padStart(2, '0')}:${timeState.minute.toString().padStart(2, '0')}") },
            trailingContent = { Switch(reminderEnabled, { reminderEnabled = it }) },
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { showTimePicker = true }
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (name.isNotBlank() && value.isNotBlank()) {
                    focus.clearFocus()
                    onAdd(Supplement(name = name.trim(), emoji = emoji, doseValue = value.trim(), doseUnit = unit, targetDoses = if (unit == "pills") target.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 1, timeOfDay = timing, reminderHour = timeState.hour, reminderMinute = timeState.minute, reminderEnabled = reminderEnabled))
                }
            }, Modifier.fillMaxWidth().height(54.dp)
        ) { Text("Add to stack") }
        Spacer(Modifier.height(12.dp))
    }

    if (showTimePicker) {
        TimePickerDialog(
            state = timeState,
            onDismiss = { showTimePicker = false },
            onConfirm = { showTimePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSupplementDialog(supplement: Supplement, onDismiss: () -> Unit, onSave: (Supplement) -> Unit, onDeleteTrigger: () -> Unit) {
    var name by remember { mutableStateOf(supplement.name) }
    var value by remember { mutableStateOf(supplement.doseValue) }
    var unit by remember { mutableStateOf(supplement.doseUnit) }
    var target by remember { mutableStateOf(supplement.targetDoses.toString()) }
    var partition by remember { mutableStateOf(supplement.timeOfDay) }
    var reminderEnabled by remember { mutableStateOf(supplement.reminderEnabled) }
    var showTimePicker by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(supplement.reminderHour, supplement.reminderMinute, true)
    val units = listOf("mg", "pills", "IU", "g", "mcg")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit supplement", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.animateContentSize()) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text("Dose") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded, { expanded = !expanded }, Modifier.weight(1f)) {
                        OutlinedTextField(unit, {}, Modifier.menuAnchor(), readOnly = true, label = { Text("Unit") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, singleLine = true)
                        ExposedDropdownMenu(expanded, { expanded = false }) { units.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { unit = u; expanded = false }) } }
                    }
                }
                if (unit == "pills") OutlinedTextField(target, { target = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Pills per day") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(partition == DayPartition.MORNING, { partition = DayPartition.MORNING }, label = { Text("Morning") })
                    FilterChip(partition == DayPartition.EVENING, { partition = DayPartition.EVENING }, label = { Text("Evening") })
                }
                ListItem(
                    leadingContent = { Icon(Icons.Default.Alarm, null) },
                    headlineContent = { Text("Reminder at ${timeState.hour.toString().padStart(2, '0')}:${timeState.minute.toString().padStart(2, '0')}") },
                    supportingContent = { Text("Tap to change the daily time") },
                    trailingContent = { Switch(reminderEnabled, { reminderEnabled = it }) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { showTimePicker = true }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(supplement.copy(name = name.trim(), doseValue = value.trim(), doseUnit = unit, targetDoses = if (unit == "pills") target.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 1, timeOfDay = partition, reminderHour = timeState.hour, reminderMinute = timeState.minute, reminderEnabled = reminderEnabled))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDeleteTrigger) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
    )
    if (showTimePicker) TimePickerDialog(timeState, { showTimePicker = false }, { showTimePicker = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(state: TimePickerState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) }
    )
}

@Composable
fun NotificationSettingsDialog(notificationsGranted: Boolean, onDismiss: () -> Unit, onRequestPermission: () -> Unit, onOpenExactAlarmSettings: () -> Unit) {
    val context = LocalContext.current
    val exactSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val exactAllowed = !exactSupported || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (notificationsGranted) Icons.Default.Notifications else Icons.Default.NotificationsOff, null) },
        title = { Text("Reminder settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ListItem(
                    headlineContent = { Text("Notifications") },
                    supportingContent = { Text(if (notificationsGranted) "Permission is enabled" else "Android notification permission is off") },
                    trailingContent = { if (!notificationsGranted) TextButton(onClick = onRequestPermission) { Text("Enable") } }
                )
                ListItem(
                    headlineContent = { Text("Timing accuracy") },
                    supportingContent = { Text(if (exactAllowed) "Exact alarms are available" else "Using battery-friendly inexact alarms") },
                    trailingContent = { if (exactSupported && !exactAllowed) TextButton(onClick = onOpenExactAlarmSettings) { Text("Allow exact") } }
                )
                Text("Each supplement can also have its own reminder switch and time. Long-press a card to edit it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun EmptyStateView(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(104.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text("💊", fontSize = 48.sp) }
        Spacer(Modifier.height(20.dp))
        Text("Your stack is empty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Add your first supplement and Suppli will keep the daily routine and reminders in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add supplement") }
    }
}

@Composable
fun DropZonePlaceholder(txt: String) {
    Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Text(txt, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
