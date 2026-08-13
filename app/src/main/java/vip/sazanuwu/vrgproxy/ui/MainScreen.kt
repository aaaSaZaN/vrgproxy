package vip.sazanuwu.vrgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vip.sazanuwu.vrgproxy.net.NetInfo
import vip.sazanuwu.vrgproxy.service.ProxyController
import vip.sazanuwu.vrgproxy.service.ProxyController.State

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val status by ProxyController.status.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("VRG Прокси", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            PowerButton(status.state) {
                if (status.state == State.RUNNING || status.state == State.STARTING) {
                    ProxyController.requestStop(context)
                } else {
                    ProxyController.requestStart(context)
                }
            }

            Spacer(Modifier.height(16.dp))
            StateLabel(status)
            Spacer(Modifier.height(24.dp))

            AnimatedVisibility(visible = status.vpnActive) {
                VpnWarningCard()
            }

            AnimatedVisibility(visible = status.state == State.RUNNING) {
                AddressCard(status, context)
            }

            AnimatedVisibility(visible = status.state == State.ERROR) {
                ErrorCard(status.error.orEmpty()) { showLog = true }
            }

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(visible = status.state == State.RUNNING) {
                StatsRow(status)
            }

            AnimatedVisibility(visible = status.state == State.RUNNING && status.nodes.isNotEmpty()) {
                ServerPicker(status)
            }

            Spacer(Modifier.height(20.dp))

            RowButton("Как подключить Quest 3") { showHelp = true }
            Spacer(Modifier.height(10.dp))
            RowButton("Дополнительно") { showSettings = true }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    if (showHelp) HelpSheet(status.proxyLine) { showHelp = false }
    if (showLog) LogDialog { showLog = false }
}

// ------------------------------------------------------------------ кнопка

@Composable
private fun PowerButton(state: State, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = when (state) {
            State.RUNNING -> MaterialTheme.colorScheme.primary
            State.STARTING -> MaterialTheme.colorScheme.secondary
            State.ERROR -> MaterialTheme.colorScheme.error
            State.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "power"
    )
    val onColor = if (state == State.STOPPED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Box(
        modifier = Modifier
            .padding(top = 24.dp)
            .size(190.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
            .clickable(enabled = state != State.STARTING, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (state == State.STARTING) {
            CircularProgressIndicator(color = onColor, strokeWidth = 3.dp)
        } else {
            Text(
                text = if (state == State.RUNNING) "ВЫКЛЮЧИТЬ" else "ВКЛЮЧИТЬ",
                color = onColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StateLabel(status: ProxyController.Status) {
    val text = when (status.state) {
        State.RUNNING -> "Раздача работает"
        State.STARTING -> status.progress.ifEmpty { "Запускаю…" }
        State.ERROR -> "Не удалось запустить"
        State.STOPPED -> if (ProxyController.prefs().subscriptionUrl.isBlank()) {
            "Открой «Дополнительно» и вставь ссылку на подписку"
        } else {
            "Нажми, чтобы раздать интернет"
        }
    }
    Text(
        text = text,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

// ------------------------------------------------------------------ адрес

@Composable
private fun AddressCard(status: ProxyController.Status, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Впиши это в настройках сети на Quest",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(14.dp))

            val primary = status.addresses.firstOrNull()
            FieldRow(
                // Подписываем адрес источником: при поднятой точке доступа и
                // Wi-Fi одновременно адреса разные, и нужен тот, к которому
                // подключён шлем.
                label = primary?.let { "Адрес прокси · ${it.label}" } ?: "Адрес прокси",
                value = primary?.ip ?: "нет сети",
                context = context
            )
            Spacer(Modifier.height(10.dp))
            FieldRow("Порт", status.port.toString(), context)

            status.addresses.drop(1).forEach { extra ->
                Spacer(Modifier.height(10.dp))
                FieldRow("Адрес прокси · ${extra.label}", extra.ip, context)
            }

            if (status.addresses.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Телефон не подключён к сети. Подключи телефон и Quest к одной " +
                            "сети или раздай точку доступа с телефона.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Роутер не нужен: телефон может раздать свою мобильную сеть " +
                            "точкой доступа, а Quest подключить к ней.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { openTetherSettings(context) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Открыть настройки точки доступа", fontSize = 14.sp)
                }

                Spacer(Modifier.height(6.dp))
                // Показываем, что видит система: иначе непонятно, телефон правда
                // не в сети или приложение не распознало интерфейс.
                Text(
                    "Что видит приложение:\n${NetInfo.describeInterfaces()}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Пока на телефоне работает VPN, раздача бесполезна: ядро слушает порт и с
 * самого телефона всё работает, но ответы клиентам уходят в туннель и до них
 * не доходят. Снаружи это выглядит как «порт закрыт», и человек будет искать
 * проблему в Quest, а не в телефоне.
 */
@Composable
private fun VpnWarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        )
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "Выключи VPN на телефоне",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Пока включён другой VPN, Quest не сможет подключиться: " +
                            "ответы уходят в туннель вместо локальной сети. " +
                            "Этому приложению VPN не нужен — оно и так ходит через сервер.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(onClick = { copy(context, value) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать")
        }
    }
}

// ------------------------------------------------------------------ статистика

@Composable
private fun StatsRow(status: ProxyController.Status) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Stat("Клиентов", status.clients.toString())
        Stat("Скачано", formatBytes(status.down))
        Stat("Отдано", formatBytes(status.up))
        Stat("Пинг", status.ping?.let { "$it мс" } ?: "…")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

// ------------------------------------------------------------------ выбор сервера

/**
 * Выбор ноды прямо на главном экране: менять сервер приходится часто,
 * прятать это в «Дополнительно» неудобно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPicker(status: ProxyController.Status) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Сервер",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { ProxyController.checkServers() },
                enabled = !status.checking
            ) {
                if (status.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверяю…", fontSize = 13.sp)
                } else {
                    Icon(
                        Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Проверить", fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            status.nodes.forEach { node ->
                val selected = node == status.currentNode
                val measured = status.pings[node]
                val wasChecked = status.pings.containsKey(node)
                FilterChip(
                    selected = selected,
                    onClick = { ProxyController.selectNode(node) },
                    label = {
                        Column {
                            Text(node, fontSize = 14.sp)
                            when {
                                measured != null -> Text(
                                    "$measured мс",
                                    fontSize = 11.sp,
                                    color = pingColor(measured)
                                )
                                wasChecked -> Text(
                                    "не отвечает",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------------ ошибка

@Composable
private fun ErrorCard(message: String, onShowLog: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            // Показываем сообщение целиком: раньше бралась только первая строка,
            // и причина ошибки до пользователя не доходила.
            Text(message, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onShowLog) { Text("Показать журнал ядра") }
        }
    }
}

@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Журнал ядра") },
        text = {
            Text(
                ProxyController.coreLog().takeLast(3000).ifEmpty { "Пусто" },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }
    )
}

// ------------------------------------------------------------------ кнопки-строки

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, Modifier.weight(1f), fontSize = 15.sp)
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(-90f)
            )
        }
    }
}

// ------------------------------------------------------------------ инструкция

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(proxyLine: String, onDismiss: () -> Unit) {
    val host = proxyLine.substringBefore(':')
    val port = proxyLine.substringAfter(':')
    var tab by remember { mutableIntStateOf(0) }

    // Новый шлем и уже настроенный — это два разных пути. На новом прокси
    // вписывается прямо при первом подключении к сети: экрана настроек ещё нет,
    // а без прокси шлем не пройдёт активацию.
    val freshSteps = listOf(
        "Сначала включи раздачу здесь — большой кнопкой. Телефон должен быть в той же сети, к которой будешь подключать шлем.",
        "Надень шлем. Дойди до экрана выбора сети Wi-Fi.",
        "Выбери свою сеть и введи пароль от неё, но НЕ нажимай «Подключиться».",
        "На этом же экране открой дополнительные (расширенные) настройки.",
        "В пункте «Прокси» выбери ручную настройку.",
        "Хост: $host\nПорт: $port",
        "Теперь нажимай «Подключиться» и проходи активацию как обычно."
    )

    val existingSteps = listOf(
        "Включи раздачу здесь — большой кнопкой.",
        "В шлеме: Настройки → Wi-Fi.",
        "Нажми на сеть, к которой подключён шлем — ту же, что и телефон.",
        "Открой дополнительные настройки сети → «Прокси» → вручную.",
        "Хост: $host\nПорт: $port",
        "Сохрани. Магазин Meta и приложения заработают."
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp)) {
            Text("Подключение Quest", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                Tab(selected = tab == 0, onClick = { tab = 0 }) {
                    Text("Новый шлем", Modifier.padding(vertical = 12.dp), fontSize = 14.sp)
                }
                Tab(selected = tab == 1, onClick = { tab = 1 }) {
                    Text("Уже настроен", Modifier.padding(vertical = 12.dp), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))

            if (tab == 0) {
                Text(
                    "Прокси надо вписать до того, как шлем подключится к сети — " +
                            "иначе он не пройдёт активацию.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
            }

            (if (tab == 0) freshSteps else existingSteps).forEachIndexed { index, step ->
                Row(Modifier.padding(vertical = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${index + 1}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(step, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Названия пунктов на разных прошивках отличаются, но смысл один: " +
                        "расширенные настройки сети → прокси → вручную. " +
                        "Телефон всё время должен быть включён и в этой же сети.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ------------------------------------------------------------------ настройки

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { ProxyController.prefs() }
    val status by ProxyController.status.collectAsStateWithLifecycle()

    var url by remember { mutableStateOf(prefs.subscriptionUrl) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var user by remember { mutableStateOf(prefs.authUser) }
    var pass by remember { mutableStateOf(prefs.authPass) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Дополнительно", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Ссылка на подписку") },
                singleLine = true,
                supportingText = { Text("Из неё берутся серверы. Всё остальное настроено внутри") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = port,
                onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                label = { Text("Порт") },
                singleLine = true,
                supportingText = { Text("Порты ниже 1024 Android не разрешает") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            Text(
                "Пароль на прокси — нужен только в чужой сети",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Логин (пусто — без пароля)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Пароль") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    prefs.subscriptionUrl = url
                    prefs.port = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 7890
                    prefs.authUser = user
                    prefs.authPass = pass
                    ProxyController.applySettingsAndRestart(context)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Сохранить и перезапустить")
            }
        }
    }
}

// ------------------------------------------------------------------ утилиты

/**
 * Открывает системный экран точки доступа.
 *
 * Включить её сама программа не может: Android не даёт обычным приложениям
 * управлять хотспотом, а startLocalOnlyHotspot поднимает сеть без интернета,
 * что для раздачи бесполезно. Поэтому просто отводим пользователя куда надо.
 */
private fun openTetherSettings(context: Context) {
    val candidates = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Экран называется по-разному у разных производителей — пробуем следующий.
        }
    }
    Toast.makeText(context, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
}

private fun copy(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("proxy", value))
    Toast.makeText(context, "Скопировано: $value", Toast.LENGTH_SHORT).show()
}

/** Зелёный — быстро, жёлтый — терпимо, красный — играть будет больно. */
@Composable
private fun pingColor(ping: Int): Color = when {
    ping < 150 -> MaterialTheme.colorScheme.primary
    ping < 300 -> Color(0xFFE0A030)
    else -> MaterialTheme.colorScheme.error
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
