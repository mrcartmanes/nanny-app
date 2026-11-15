package ru.gdebyl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.gdebyl.ui.*
import ru.gdebyl.data.*
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var permissionTick by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }
    override fun onResume() { super.onResume(); permissionTick++ }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8DD6C4), secondary = Color(0xFFB7CDC6)) else lightColorScheme(primary = Color(0xFF277868), secondary = Color(0xFF526960), background = Color(0xFFF7F9F6), surface = Color(0xFFF7F9F6))) {
                val vm: DiaryViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var onboarding by rememberSaveable { mutableStateOf(!(application as GdeBylApp).preferences.value.onboarded) }
                val fine = remember(permissionTick) { granted(Manifest.permission.ACCESS_FINE_LOCATION) }
                LaunchedEffect(permissionTick) {
                    if (fine && (application as GdeBylApp).preferences.value.recording) vm.start()
                }
                Surface(Modifier.fillMaxSize()) {
                    if (onboarding) Onboarding(fine, permissionTick, ::granted, { permissions.launch(it) }, { openSettings() }, { vm.onboarded(); onboarding = false })
                    else DiaryApp(state, vm, onStart = { if (fine) vm.start() else onboarding = true }, onPermissions = { onboarding = true }, onBattery = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
                }
            }
        }
    }
    private fun openSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
}

@Composable
private fun Onboarding(fine: Boolean, tick: Int, granted: (String) -> Boolean, request: (Array<String>) -> Unit, settings: () -> Unit, done: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val bg = Build.VERSION.SDK_INT < 29 || remember(tick) { granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val notifications = Build.VERSION.SDK_INT < 33 || remember(tick) { granted(Manifest.permission.POST_NOTIFICATIONS) }
    val motion = Build.VERSION.SDK_INT < 29 || remember(tick) { granted(Manifest.permission.ACTIVITY_RECOGNITION) }
    val titles = listOf(R.string.welcome, R.string.permission_location, R.string.permission_background, R.string.permission_notifications, R.string.permission_motion, R.string.battery_title)
    val texts = listOf(R.string.welcome_text, R.string.permission_location_text, R.string.permission_background_text, R.string.permission_notifications_text, R.string.permission_motion_text, R.string.battery_text)
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Default.LocationOn, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.setup_step, step + 1, 6), style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { (step + 1) / 6f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(titles[step]), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(texts[step]), style = MaterialTheme.typography.bodyLarge)
        if (step == 5) BatteryInstructions()
        val allowed = when (step) { 1 -> fine; 2 -> bg; 3 -> notifications; 4 -> motion; else -> true }
        if (!allowed) {
            Button(onClick = {
                when (step) {
                    1 -> request(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    2 -> if (Build.VERSION.SDK_INT == 29) request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) else settings()
                    3 -> if (Build.VERSION.SDK_INT >= 33) request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    4 -> if (Build.VERSION.SDK_INT >= 29) request(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (step == 2 && Build.VERSION.SDK_INT >= 30) R.string.open_settings else R.string.allow)) }
            if (step == 1) TextButton(onClick = settings) { Text(stringResource(R.string.permission_settings)) }
        }
        Button(onClick = { if (step == 5) done() else step++ }, enabled = step != 1 || fine, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (step == 5) R.string.ready else if (!allowed && step != 1) R.string.skip else R.string.next))
        }
        if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
    }
}
@Composable
private fun BatteryInstructions() {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val text = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> R.string.battery_xiaomi
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> R.string.battery_huawei
        manufacturer.contains("samsung") -> R.string.battery_samsung
        else -> R.string.battery_other
    }
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryApp(state: DiaryState, vm: DiaryViewModel, onStart: () -> Unit, onPermissions: () -> Unit, onBattery: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var editing by remember { mutableStateOf<VisitWithPlace?>(null) }
    val titles = listOf(R.string.home, R.string.feed, R.string.statistics, R.string.settings)
    val icons = listOf(Icons.Default.LocationOn, Icons.Default.DateRange, Icons.Default.BarChart, Icons.Default.Settings)
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) }, actions = { Text(stringResource(titles[tab]), Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelLarge) }) },
        snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar { titles.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(stringResource(title)) }) } }
        }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> Home(state, onStart, vm::stop, { tab = 1 })
                1 -> Feed(state, LocalDate.parse(date), { date = it.toString() }, { editing = it })
                2 -> Statistics(state, LocalDate.parse(date), { date = it.toString() }, vm::export)
                3 -> SettingsScreen(state, vm, onPermissions, onBattery)
            }
        }
    }
    editing?.let { entry -> VisitEditor(entry, { editing = null }, { name, start, end -> vm.edit(entry, name, start, end); editing = null }, { vm.delete(entry); editing = null }) }
}
@Composable
private fun duration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) stringResource(R.string.hours_minutes, minutes / 60, minutes % 60) else stringResource(R.string.minutes, minutes)
}
private fun clock(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
@Composable
private fun Home(state: DiaryState, start: () -> Unit, stop: () -> Unit, feed: () -> Unit) {
    val active = state.visits.lastOrNull { it.visit.endTs == null }
    val today = LocalDate.now()
    val segments = timeline(state.visits, state.sessions, dayStart(today), dayStart(today.plusDays(1)), state.now)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(today.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", java.util.Locale("ru"))), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(40.dp))
                Text(active?.place?.name ?: stringResource(if (state.settings.recording) R.string.finding_place else R.string.your_day), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (active != null) stringResource(R.string.here_for, duration(state.now - active.visit.startTs)) else stringResource(if (state.settings.recording) R.string.recording_hint else R.string.start_hint))
            }
        }
        Button(onClick = if (state.settings.recording) stop else start, shape = CircleShape, modifier = Modifier.size(168.dp), colors = if (state.settings.recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (state.settings.recording) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(40.dp))
                Text(stringResource(if (state.settings.recording) R.string.stop else R.string.start), style = MaterialTheme.typography.titleLarge)
            }
        }
        state.settings.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric(stringResource(R.string.in_places), duration(segments.filter { it.entry != null }.sumOf { it.end - it.start }))
            Metric(stringResource(R.string.on_road), duration(segments.filter { it.entry == null }.sumOf { it.end - it.start }))
        }
        TextButton(onClick = feed) { Text(stringResource(R.string.open_feed)) }
        Text(stringResource(R.string.local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Metric(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.bodySmall) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelector(date: LocalDate, change: (LocalDate) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { change(date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.previous_day)) }
        TextButton(onClick = { picker = true }) { Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("ru")))) }
        IconButton(onClick = { change(date.plusDays(1)) }, enabled = date < LocalDate.now()) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.next_day)) }
    }
    if (picker) {
        val value = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = { TextButton(onClick = { value.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().coerceAtMost(LocalDate.now())) }; picker = false }) { Text(stringResource(R.string.select)) } }, dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(state = value) }
    }
}
@Composable
private fun Feed(state: DiaryState, date: LocalDate, change: (LocalDate) -> Unit, edit: (VisitWithPlace) -> Unit) {
    val segments = timeline(state.visits, state.sessions, dayStart(date), dayStart(date.plusDays(1)), state.now)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        DateSelector(date, change)
        if (segments.isEmpty()) EmptyState(R.string.no_visits)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(segments) { s ->
                if (s.entry == null) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsWalk, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.road_duration, duration(s.end - s.start)), style = MaterialTheme.typography.bodyMedium)
                    Text("${clock(s.start)}–${clock(s.end)}", style = MaterialTheme.typography.bodySmall)
                } else Card(onClick = { edit(s.entry) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${clock(s.start)}–${if (s.entry.visit.endTs == null && date == LocalDate.now()) stringResource(R.string.now) else clock(s.end)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(s.entry.place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        s.entry.place.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(duration(s.end - s.start), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
@Composable private fun EmptyState(text: Int) { Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Icon(Icons.Default.Explore, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary); Text(stringResource(text), style = MaterialTheme.typography.bodyLarge) } }

@Composable
private fun Statistics(state: DiaryState, date: LocalDate, change: (LocalDate) -> Unit, export: (Long, Long) -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(1) }
    val from = when (period) { 0 -> date; 1 -> date.with(java.time.DayOfWeek.MONDAY); else -> date.withDayOfMonth(1) }
    val until = when (period) { 0 -> from.plusDays(1); 1 -> from.plusWeeks(1); else -> from.plusMonths(1) }
    val segments = timeline(state.visits, state.sessions, dayStart(from), dayStart(until), state.now)
    val totals = segments.filter { it.entry != null }.groupBy { it.entry!!.place.id }.values.map { rows -> rows.first().entry!!.place to rows.sumOf { it.end - it.start } }.sortedByDescending { it.second }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DateSelector(date, change)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(R.string.day, R.string.week, R.string.month).forEachIndexed { i, label -> FilterChip(selected = period == i, onClick = { period = i }, label = { Text(stringResource(label)) }) } }
        Text("${from.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${until.minusDays(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric(stringResource(R.string.in_places), duration(totals.sumOf { it.second }))
            Metric(stringResource(R.string.on_road), duration(segments.filter { it.entry == null }.sumOf { it.end - it.start }))
        }
        if (totals.isEmpty()) EmptyState(R.string.no_statistics)
        totals.forEachIndexed { index, (place, time) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${index + 1}. ${place.name}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(duration(time)) }
                LinearProgressIndicator(progress = { time.toFloat() / totals.first().second.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().height(12.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        OutlinedButton(onClick = { export(dayStart(from), dayStart(until)) }, enabled = totals.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.export)) }
    }
}

@Composable
private fun SettingsScreen(state: DiaryState, vm: DiaryViewModel, permissions: () -> Unit, battery: () -> Unit) {
    var radius by rememberSaveable(state.settings.radius) { mutableStateOf(state.settings.radius.toString()) }
    var minutes by rememberSaveable(state.settings.minutes) { mutableStateOf(state.settings.minutes.toString()) }
    var confirmClear by remember { mutableStateOf(false) }
    var selectedPlace by remember { mutableStateOf<Place?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.detection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = radius, onValueChange = { radius = it }, label = { Text(stringResource(R.string.radius)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        OutlinedTextField(value = minutes, onValueChange = { minutes = it }, label = { Text(stringResource(R.string.minimum_stop)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        val valid = (radius.toIntOrNull() ?: 0) in 10..1000 && (minutes.toIntOrNull() ?: 0) in 1..120
        Text(stringResource(R.string.settings_limits), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.settings(radius.toInt(), minutes.toInt()) }, enabled = valid && !state.settings.recording, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        if (state.settings.recording) Text(stringResource(R.string.stop_to_configure), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.places), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (state.places.isEmpty()) Text(stringResource(R.string.no_places))
        state.places.forEach { place ->
            ListItem(headlineContent = { Text(place.name) }, supportingContent = { Text(place.address ?: stringResource(R.string.coordinates, place.lat, place.lon)) }, trailingContent = { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }, modifier = Modifier.clickable { selectedPlace = place })
        }
        HorizontalDivider()
        TextButton(onClick = permissions) { Text(stringResource(R.string.permissions)) }
        Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
        BatteryInstructions()
        OutlinedButton(onClick = battery) { Text(stringResource(R.string.battery_settings)) }
        Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirmClear = true }, enabled = !state.settings.recording, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_all)) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.delete_all)) }, text = { Text(stringResource(R.string.delete_all_text)) }, confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
    selectedPlace?.let { place -> PlaceEditor(place, state.places, { selectedPlace = null }, { vm.rename(place.id, it); selectedPlace = null }, { vm.merge(place.id, it); selectedPlace = null }) }
}
@Composable
private fun PlaceEditor(place: Place, places: List<Place>, dismiss: () -> Unit, rename: (String) -> Unit, merge: (Long) -> Unit) {
    var name by remember(place.id) { mutableStateOf(place.name) }
    var merging by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<Place?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.edit_place)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.place_name)) }, singleLine = true)
            Text(stringResource(R.string.rename_explanation), style = MaterialTheme.typography.bodySmall)
            if (places.size > 1) TextButton(onClick = { merging = !merging }) { Text(stringResource(R.string.merge_place)) }
            if (merging) { Text(stringResource(R.string.choose_target)); places.filter { it.id != place.id }.forEach { target -> TextButton(onClick = { mergeTarget = target }) { Text(target.name) } } }
        }
    }, confirmButton = { TextButton(onClick = { rename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
    mergeTarget?.let { target -> AlertDialog(onDismissRequest = { mergeTarget = null }, title = { Text(stringResource(R.string.merge_place)) }, text = { Text(stringResource(R.string.merge_confirmation, place.name, target.name)) }, confirmButton = { TextButton(onClick = { merge(target.id) }) { Text(stringResource(R.string.merge)) } }, dismissButton = { TextButton(onClick = { mergeTarget = null }) { Text(stringResource(R.string.cancel)) } }) }
}
private val editFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
private fun editTime(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(editFormat)
private fun parseTime(text: String): Long? = runCatching { LocalDateTime.parse(text, editFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
@Composable
private fun VisitEditor(entry: VisitWithPlace, dismiss: () -> Unit, save: (String, Long, Long?) -> Unit, delete: () -> Unit) {
    var name by remember(entry.visit.id) { mutableStateOf(entry.place.name) }
    var start by remember(entry.visit.id) { mutableStateOf(editTime(entry.visit.startTs)) }
    var end by remember(entry.visit.id) { mutableStateOf(entry.visit.endTs?.let(::editTime).orEmpty()) }
    var confirm by remember { mutableStateOf(false) }
    val open = entry.visit.endTs == null
    val parsedStart = if (open) entry.visit.startTs else parseTime(start)
    val parsedEnd = if (open) null else parseTime(end)
    val valid = name.isNotBlank() && parsedStart != null && (open || (parsedEnd != null && parsedEnd > parsedStart && parsedEnd <= System.currentTimeMillis()))
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.edit_visit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.place_name)) }, singleLine = true)
            Text(stringResource(R.string.rename_explanation), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.start_time)) }, enabled = !open, singleLine = true)
            if (!open) OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.end_time)) }, singleLine = true)
            Text(stringResource(if (open) R.string.open_visit_hint else R.string.time_format), style = MaterialTheme.typography.bodySmall)
            if (!valid) Text(stringResource(R.string.invalid_visit), color = MaterialTheme.colorScheme.error)
            if (!open) TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.delete_visit), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { save(name, parsedStart!!, parsedEnd) }, enabled = valid) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.delete_visit)) }, text = { Text(stringResource(R.string.delete_visit_text)) }, confirmButton = { TextButton(onClick = delete) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
