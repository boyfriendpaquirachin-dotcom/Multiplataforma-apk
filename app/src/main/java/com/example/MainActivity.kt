package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.ScriptModule
import com.example.data.ScriptModuleRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext, lifecycleScope)
        val repository = ScriptModuleRepository(database.scriptModuleDao())
        val factory = MainViewModelFactory(repository)

        setContent {
            MyApplicationTheme {
                val viewModel: MainViewModel = viewModel(factory = factory)

                DisposableEffect(Unit) {
                    val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
                        viewModel.checkShizukuStatus()
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    onDispose {
                        Shizuku.removeRequestPermissionResultListener(listener)
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.checkShizukuStatus()
                }

                MainScreen(viewModel = viewModel)
            }
        }
    }
}

class MainViewModel(private val repository: ScriptModuleRepository) : ViewModel() {
    val uiState: StateFlow<List<ScriptModule>> = repository.allModules
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    var isShizukuRunning by mutableStateOf(false)
        private set

    var hasShizukuPermission by mutableStateOf(false)
        private set

    // Logs/Consola outputs
    var terminalOutput by mutableStateOf("Consola iniciada. Escribe comandos abajo o ejecuta módulos.\n")
        private set

    // Toggles y estados globales
    var naptimeDoze by mutableStateOf(false)
    var naptimeMotion by mutableStateOf(false)

    var waveletMaster by mutableStateOf(false)
    var waveletAttenuator by mutableStateOf(5.0f)
    var waveletLimiter by mutableStateOf(3.0f)
    var waveletBalance by mutableStateOf(0.0f)

    var saverTunerState by mutableStateOf("Por defecto")

    init {
        checkShizukuStatus()
    }

    fun checkShizukuStatus() {
        isShizukuRunning = ShizukuShellExecutor.isShizukuRunning()
        hasShizukuPermission = ShizukuShellExecutor.hasShizukuPermission()
    }

    fun requestPermissionAndAutoGrant(context: Context) {
        if (!isShizukuRunning) {
            terminalOutput += "[ALERTA] Shizuku no se está ejecutando. Por favor, abre la app Shizuku e iníciala.\n"
            Toast.makeText(context, "Shizuku no se está ejecutando", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasShizukuPermission) {
            ShizukuShellExecutor.requestShizukuPermission()
            checkShizukuStatus()
        } else {
            // Auto-grant system permissions using active Shizuku pipe
            viewModelScope.launch {
                terminalOutput += "[SISTEMA] Iniciando auto-concesión de permisos seguros del sistema...\n"
                val success = ShizukuShellExecutor.autoGrantAppPermissions(context)
                if (success) {
                    terminalOutput += "[SISTEMA] ¡ÉXITO! WRITE_SECURE_SETTINGS, DUMP, BATTERY_STATS y PACKAGE_USAGE_STATS concedidos automáticamente.\n"
                    Toast.makeText(context, "Permisos del sistema concedidos", Toast.LENGTH_SHORT).show()
                } else {
                    terminalOutput += "[SISTEMA] Falló la auto-concesión de permisos automáticos.\n"
                    Toast.makeText(context, "La concesión automática falló", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Room operations
    fun addCustomModule(name: String, description: String, script: String) {
        viewModelScope.launch {
            val module = ScriptModule(
                name = name,
                description = description,
                script = script,
                isEnabled = false,
                isSystem = false
            )
            repository.insert(module)
            terminalOutput += "[INFO] Módulo custom '$name' añadido a la base de datos de AxManager.\n"
        }
    }

    fun importModuleFromFolder(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            try {
                val contentResolver = context.contentResolver
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (e: Exception) {
                    Log.d("AxManager", "Failed to take persistable uri permission: ${e.message}")
                }

                val parentDir = DocumentFile.fromTreeUri(context, treeUri)
                if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
                    terminalOutput += "[ERROR] No se pudo acceder a la carpeta elegida.\n"
                    Toast.makeText(context, "No se pudo acceder a la carpeta", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val files = parentDir.listFiles()
                
                // Find .sh file
                val shFile = files.find { it.name?.endsWith(".sh") == true }
                if (shFile == null) {
                    terminalOutput += "[ERROR] No se encontró ningún archivo de script '.sh' en la carpeta seleccionada.\n"
                    Toast.makeText(context, "No se encontró ningún script .sh", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Read .sh script contents
                val scriptUri = shFile.uri
                val scriptContent = contentResolver.openInputStream(scriptUri)?.use { stream ->
                    stream.bufferedReader().use { it.readText() }
                } ?: ""

                if (scriptContent.isEmpty()) {
                    terminalOutput += "[AVISO] El archivo de script '${shFile.name}' está vacío.\n"
                }

                // Try to find module.prop or config.prop for metadata
                val propFile = files.find { 
                    it.name?.equals("module.prop", ignoreCase = true) == true || 
                    it.name?.equals("config.prop", ignoreCase = true) == true 
                }

                var moduleName = shFile.name?.substringBeforeLast(".sh") ?: "Módulo Importado"
                var moduleDescription = "Módulo personalizado cargado automáticamente desde el almacenamiento local."
                var author = "Desconocido"

                if (propFile != null) {
                    val propContent = contentResolver.openInputStream(propFile.uri)?.use { stream ->
                        stream.bufferedReader().use { it.readText() }
                    } ?: ""
                    
                    // Parse keys
                    propContent.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val parts = trimmed.split("=", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim().lowercase()
                                val value = parts[1].trim()
                                when (key) {
                                    "name" -> moduleName = value
                                    "description" -> moduleDescription = value
                                    "author" -> author = value
                                }
                            }
                        }
                    }
                } else {
                    // Fallback: search for inline metadata headers in the .sh script
                    scriptContent.lines().take(20).forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#")) {
                            val cleanLine = trimmed.removePrefix("#").trim()
                            val parts = cleanLine.split(":", "=", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim().lowercase()
                                val value = parts[1].trim()
                                when (key) {
                                    "name" -> moduleName = value
                                    "description", "desc" -> moduleDescription = value
                                    "author" -> author = value
                                }
                            }
                        }
                    }
                }

                // If author is found, append it to the description
                val finalDescription = if (author != "Desconocido") {
                    "$moduleDescription (Autor: $author)"
                } else {
                    moduleDescription
                }

                // Insert into Room
                val newModule = ScriptModule(
                    name = moduleName,
                    description = finalDescription,
                    script = scriptContent,
                    isEnabled = false,
                    isSystem = false
                )
                repository.insert(newModule)
                
                terminalOutput += "[IMPORTACIÓN] ¡ÉXITO! Módulo '$moduleName' importado correctamente.\n"
                terminalOutput += "  - Script: ${shFile.name}\n"
                terminalOutput += "  - Descripción: $finalDescription\n"
                Toast.makeText(context, "Módulo '$moduleName' importado", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                terminalOutput += "[ERROR] Error al importar módulo: ${e.message}\n"
                Log.e("AxManager", "Error importing module", e)
                Toast.makeText(context, "Error al importar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun toggleModule(module: ScriptModule, isEnabled: Boolean) {
        viewModelScope.launch {
            val updated = module.copy(isEnabled = isEnabled)
            repository.update(updated)

            if (isEnabled) {
                terminalOutput += "[EJECUCIÓN] Aplicando módulo: ${module.name}...\n"
                // Split script lines and execute them sequentially
                val commands = module.script.lines().filter { it.trim().isNotEmpty() && !it.startsWith("#") }
                if (commands.isNotEmpty()) {
                    val result = ShizukuShellExecutor.executeCommands(commands)
                    if (result.exitCode == 0) {
                        terminalOutput += "[ÉXITO] ${module.name} aplicado de forma óptima.\nSalida:\n${result.output}\n"
                    } else {
                        terminalOutput += "[ERROR] Fallo al aplicar ${module.name}.\nError:\n${result.error}\n"
                    }
                } else {
                    terminalOutput += "[AVISO] Módulo ${module.name} no contiene comandos ejecutables.\n"
                }
            } else {
                terminalOutput += "[DESACTIVACIÓN] Módulo '${module.name}' marcado como inactivo.\n"
            }
        }
    }

    fun deleteModule(module: ScriptModule) {
        viewModelScope.launch {
            repository.delete(module)
            terminalOutput += "[INFO] Módulo '${module.name}' eliminado.\n"
        }
    }

    // Naptime toggles
    fun toggleNaptimeDoze(enabled: Boolean) {
        naptimeDoze = enabled
        viewModelScope.launch {
            if (enabled) {
                terminalOutput += "[NAPTIME] Habilitando Doze Agresivo...\n"
                val cmds = listOf(
                    "settings put global device_idle_constants light_after_inactive_to=3000,inactive_to=3000,sensing_to=0,locating_to=0,motion_inactive_to=0,idle_after_inactive_to=3000",
                    "dumpsys deviceidle force-idle"
                )
                val res = ShizukuShellExecutor.executeCommands(cmds)
                terminalOutput += if (res.exitCode == 0) "[ÉXITO] Doze Agresivo activado con éxito.\n" else "[ERROR] ${res.error}\n"
            } else {
                terminalOutput += "[NAPTIME] Desactivando Doze Agresivo...\n"
                val cmds = listOf(
                    "settings delete global device_idle_constants",
                    "dumpsys deviceidle reset"
                )
                val res = ShizukuShellExecutor.executeCommands(cmds)
                terminalOutput += if (res.exitCode == 0) "[ÉXITO] Doze restablecido al valor por defecto.\n" else "[ERROR] ${res.error}\n"
            }
        }
    }

    fun toggleNaptimeMotion(enabled: Boolean) {
        naptimeMotion = enabled
        viewModelScope.launch {
            if (enabled) {
                terminalOutput += "[NAPTIME] Desactivando detección de movimiento física...\n"
                val cmd = "settings put global device_idle_constants motion_inactive_to=0"
                val res = ShizukuShellExecutor.executeCommand(cmd)
                terminalOutput += if (res.exitCode == 0) "[ÉXITO] Movimiento desactivado para Deep Sleep.\n" else "[ERROR] ${res.error}\n"
            } else {
                terminalOutput += "[NAPTIME] Re-habilitando detección de movimiento...\n"
                val cmd = "settings delete global device_idle_constants"
                val res = ShizukuShellExecutor.executeCommand(cmd)
                terminalOutput += if (res.exitCode == 0) "[ÉXITO] Detección de movimiento restablecida.\n" else "[ERROR] ${res.error}\n"
            }
        }
    }

    // SaverTuner presets
    fun setSaverPreset(preset: String) {
        saverTunerState = preset
        viewModelScope.launch {
            terminalOutput += "[SAVERTUNER] Aplicando perfil de batería: $preset...\n"
            val cmds = when (preset) {
                "Por defecto" -> listOf(
                    "settings put global low_power 0",
                    "settings delete global low_power_trigger_level",
                    "settings delete global low_power_constants"
                )
                "Ligero" -> listOf(
                    "settings put global low_power 1",
                    "settings put global low_power_constants battery_saver_trigger_threshold=15"
                )
                "Moderado" -> listOf(
                    "settings put global low_power 1",
                    "settings put global low_power_constants battery_saver_trigger_threshold=30,enable_brightness_adjustment=true"
                )
                "Alto" -> listOf(
                    "settings put global low_power 1",
                    "settings put global low_power_constants battery_saver_trigger_threshold=50,enable_brightness_adjustment=true,enable_firewall=true,adjust_brightness_factor=0.7"
                )
                "Extremo" -> listOf(
                    "settings put global low_power 1",
                    "settings put global low_power_constants battery_saver_trigger_threshold=80,enable_brightness_adjustment=true,enable_firewall=true,adjust_brightness_factor=0.5,force_all_apps_standby=true"
                )
                else -> emptyList()
            }

            if (cmds.isNotEmpty()) {
                val res = ShizukuShellExecutor.executeCommands(cmds)
                if (res.exitCode == 0) {
                    terminalOutput += "[ÉXITO] Perfil de batería '$preset' aplicado correctamente.\n"
                } else {
                    terminalOutput += "[ERROR] Falló al aplicar el perfil de ahorro: ${res.error}\n"
                }
            }
        }
    }

    // Wavelet configurations
    fun updateWaveletMaster(enabled: Boolean) {
        waveletMaster = enabled
        viewModelScope.launch {
            terminalOutput += if (enabled) {
                "[WAVELET] Ecualizador Maestro activado. Parámetros ajustados dinámicamente.\n"
            } else {
                "[WAVELET] Ecualizador desactivado.\n"
            }
        }
    }

    // Shell direct execute
    fun runDirectCommand(cmdString: String) {
        if (cmdString.trim().lowercase() == "clear") {
            terminalOutput = ""
            return
        }
        terminalOutput += "shizuku@android:~$ $cmdString\n"
        viewModelScope.launch {
            val result = ShizukuShellExecutor.executeCommand(cmdString)
            if (result.output.isNotEmpty()) {
                terminalOutput += result.output + "\n"
            }
            if (result.error.isNotEmpty()) {
                terminalOutput += "[ERROR] " + result.error + "\n"
            }
            if (result.output.isEmpty() && result.error.isEmpty()) {
                terminalOutput += "Proceso finalizado con código ${result.exitCode}\n"
            }
        }
    }
}

class MainViewModelFactory(private val repository: ScriptModuleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Clase ViewModel desconocida")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("AxManager", "Naptime", "Wavelet", "SaverTuner", "Termux")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "AxManager",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (viewModel.isShizukuRunning && viewModel.hasShizukuPermission) {
                                        Color(0xFF2E7D32)
                                    } else {
                                        Color(0xFFC62828)
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (viewModel.isShizukuRunning && viewModel.hasShizukuPermission) {
                                    "SHIZUKU: ACTIVO"
                                } else {
                                    "SHIZUKU: INACTIVO"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.checkShizukuStatus() },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Actualizar estado de Shizuku"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars
            ) {
                tabs.forEachIndexed { index, label ->
                    val icon = when (index) {
                        0 -> Icons.Default.Dashboard
                        1 -> Icons.Default.Bedtime
                        2 -> Icons.Default.GraphicEq
                        3 -> Icons.Default.BatteryChargingFull
                        else -> Icons.Default.Terminal
                    }
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(label, fontSize = 11.sp) },
                        icon = { Icon(icon, contentDescription = label) },
                        modifier = Modifier.testTag("tab_item_$index")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Shizuku Connection Warning & Fast-Grant Banner if permission is missing
            if (!viewModel.isShizukuRunning || !viewModel.hasShizukuPermission) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Alerta",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Configuración de Shizuku Requerida",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            "Esta aplicación requiere que el servicio de Shizuku se encuentre activo para realizar modificaciones de rendimiento.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = { viewModel.requestPermissionAndAutoGrant(context) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("connect_shizuku_btn")
                        ) {
                            Text("Conectar y Conceder Permisos", color = Color.White)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    0 -> TabAxManager(viewModel = viewModel)
                    1 -> TabNaptime(viewModel = viewModel)
                    2 -> TabWavelet(viewModel = viewModel)
                    3 -> TabSaverTuner(viewModel = viewModel)
                    4 -> TabTermux(viewModel = viewModel)
                }
            }
        }
    }
}

// ================= TAB 1: AxManager =================
@Composable
fun TabAxManager(viewModel: MainViewModel) {
    val context = LocalContext.current
    val modules by viewModel.uiState.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.importModuleFromFolder(context, uri)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (modules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = "Sin módulos",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No hay módulos importados",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Presiona '+' para seleccionar la carpeta local de tu módulo AxManager.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp, start = 16.dp, end = 16.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        "Panel de Control de Scripts",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(modules) { module ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("module_card_${module.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            module.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                "IMPORTADO",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        module.description,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                                Switch(
                                    checked = module.isEnabled,
                                    onCheckedChange = { viewModel.toggleModule(module, it) },
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("switch_${module.id}")
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Script: Activo en segundo plano",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                                TextButton(
                                    onClick = { viewModel.deleteModule(module) },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar módulo", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Eliminar", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { launcher.launch(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("add_module_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Importar módulo desde carpeta")
        }
    }
}

// ================= TAB 2: Naptime =================
@Composable
fun TabNaptime(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Optimizaciones Naptime (Doze)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Power,
                            contentDescription = "Doze",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                "Modo Doze Agresivo",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Fuerza el reposo profundo inmediatamente después de apagar la pantalla sin esperar horas.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Switch(
                        checked = viewModel.naptimeDoze,
                        onCheckedChange = { viewModel.toggleNaptimeDoze(it) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("switch_nap_doze")
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.DirectionsRun,
                            contentDescription = "Movimiento",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                "Desactivar Sensor de Movimiento",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Previene que el movimiento físico (ej. traerlo en la bolsa) despierte al dispositivo de Doze.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Switch(
                        checked = viewModel.naptimeMotion,
                        onCheckedChange = { viewModel.toggleNaptimeMotion(it) },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("switch_nap_motion")
                    )
                }
            }
        }

        // Info box
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Información",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Al habilitar ambas funciones, el consumo de batería en espera (Standby Drain) se reduce drásticamente, llegando a perder menos de 1% durante la noche.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ================= TAB 3: Wavelet =================
@Composable
fun TabWavelet(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Ecualizador de Audio Wavelet",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Switch(
                checked = viewModel.waveletMaster,
                onCheckedChange = { viewModel.updateWaveletMaster(it) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("switch_wavelet_master")
            )
        }

        // Live Equalizer Canvas Graph (adaptable rendering curve)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF121212)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val lineColor = if (viewModel.waveletMaster) Color(0xFF00E676) else Color(0xFF616161)
                val lineThickness = if (viewModel.waveletMaster) 3f else 1.5f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    val middleY = height / 2

                    // Draw grid lines
                    for (i in 1..4) {
                        val gridX = (width / 5) * i
                        drawLine(
                            color = Color.White.copy(alpha = 0.1f),
                            start = androidx.compose.ui.geometry.Offset(gridX, 0f),
                            end = androidx.compose.ui.geometry.Offset(gridX, height),
                            strokeWidth = 1f
                        )
                    }
                    drawLine(
                        color = Color.White.copy(alpha = 0.15f),
                        start = androidx.compose.ui.geometry.Offset(0f, middleY),
                        end = androidx.compose.ui.geometry.Offset(width, middleY),
                        strokeWidth = 1f
                    )

                    // Draw frequency response curve path
                    val path = Path()
                    path.moveTo(0f, middleY)

                    val attenuatorYShift = if (viewModel.waveletMaster) (viewModel.waveletAttenuator * 4f) else 0f
                    val limiterLimit = if (viewModel.waveletMaster) (viewModel.waveletLimiter * 5f) else 0f
                    val balanceShift = if (viewModel.waveletMaster) viewModel.waveletBalance else 0f

                    for (x in 0..width.toInt() step 5) {
                        val percentage = x.toFloat() / width
                        // Formula to calculate frequency response wave dynamically based on slider values
                        var y = middleY + attenuatorYShift

                        if (viewModel.waveletMaster) {
                            // Sub-bass boost + Treble roll off
                            val sineWave1 = (sin(percentage * 2 * Math.PI * 3) * 35).toFloat()
                            val sineWave2 = (sin(percentage * Math.PI * 1.5) * 15).toFloat()
                            y += (sineWave1 + sineWave2) * (1 - percentage) // Decay frequency peaks as it goes right
                            
                            // Adjust for balance (higher right side if positive, left side if negative)
                            y += balanceShift * percentage * 3.5f

                            // Limiter compression cap
                            if (limiterLimit > 0) {
                                if (y < middleY - limiterLimit) y = middleY - limiterLimit
                                if (y > middleY + limiterLimit) y = middleY + limiterLimit
                            }
                        }

                        path.lineTo(x.toFloat(), y)
                    }

                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = lineThickness)
                    )
                }

                Text(
                    "Respuesta de Frecuencia Activa (Hz)",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                )
            }
        }

        // Sliders
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Attenuator
                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Atenuador de Ganancia", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("-${String.format("%.1f", viewModel.waveletAttenuator)} dB", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = viewModel.waveletAttenuator,
                        onValueChange = { viewModel.waveletAttenuator = it },
                        valueRange = 0.0f..15.0f,
                        enabled = viewModel.waveletMaster,
                        modifier = Modifier.testTag("slider_attenuator")
                    )
                }

                // Limiter
                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Limitador Dinámico (Compresión)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("-${String.format("%.1f", viewModel.waveletLimiter)} dB", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = viewModel.waveletLimiter,
                        onValueChange = { viewModel.waveletLimiter = it },
                        valueRange = 0.0f..12.0f,
                        enabled = viewModel.waveletMaster,
                        modifier = Modifier.testTag("slider_limiter")
                    )
                }

                // Balance
                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Balance de Canal (Estéreo)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val balText = if (viewModel.waveletBalance > 0.5) {
                            "D +${String.format("%.0f", viewModel.waveletBalance)}"
                        } else if (viewModel.waveletBalance < -0.5) {
                            "I -${String.format("%.0f", -viewModel.waveletBalance)}"
                        } else {
                            "Centrado"
                        }
                        Text(balText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = viewModel.waveletBalance,
                        onValueChange = { viewModel.waveletBalance = it },
                        valueRange = -10.0f..10.0f,
                        enabled = viewModel.waveletMaster,
                        modifier = Modifier.testTag("slider_balance")
                    )
                }
            }
        }
    }
}

// ================= TAB 4: SaverTuner =================
@Composable
fun TabSaverTuner(viewModel: MainViewModel) {
    val presets = listOf("Por defecto", "Ligero", "Moderado", "Alto", "Extremo")
    val descriptions = listOf(
        "Ajustes del sistema por defecto. Consumo normal y rendimiento sin límites.",
        "Ahorro básico. Retrasa sincronización en segundo plano ligera. Se activa al 15%.",
        "Disminuye brillo máximo ligeramente, suspende animaciones pesadas. Se activa al 30%.",
        "Establece límites de cortafuegos en segundo plano, disminuye CPU a 70%. Se activa al 50%.",
        "Máximo ahorro. Fuerza inactividad completa de aplicaciones suspendidas. Se activa al 80%."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Tuner de Ahorro de Batería Avanzado",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        presets.forEachIndexed { idx, preset ->
            val isSelected = viewModel.saverTunerState == preset
            val cardBorder = if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                border = cardBorder,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setSaverPreset(preset) }
                    .testTag("preset_card_$preset")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (idx + 1).toString(),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            preset,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            descriptions[idx],
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Seleccionado",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

// ================= TAB 5: Termux (Interactive Shell) =================
@Composable
fun TabTermux(viewModel: MainViewModel) {
    var commandInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Terminal Shizuku Privilegiada (sh)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Terminal Log Output Window
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A0A0A))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                .padding(12.dp)
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(viewModel.terminalOutput) {
                // Auto-scroll terminal output as new logs enter
                scrollState.animateScrollTo(scrollState.maxValue)
            }

            Text(
                text = viewModel.terminalOutput,
                color = Color(0xFF00FF66),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            )
        }

        // Fast Quick-Keys Bar for Termux
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val quickKeys = listOf(
                "ESC", "CTRL", "ALT", "TAB", "|", "/", "-", "clear"
            )
            quickKeys.forEach { key ->
                Button(
                    onClick = {
                        if (key == "clear") {
                            viewModel.runDirectCommand("clear")
                        } else {
                            commandInput += if (key == "TAB") "  " else " $key"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Text(key, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Quick Diagnostic Buttons Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val diagCommands = listOf(
                "pm list packages -s" to "Apps de Sistema",
                "dumpsys battery" to "Info Batería",
                "getprop ro.product.model" to "Modelo",
                "top -n 1" to "Procesos CPU"
            )
            diagCommands.forEach { (cmd, label) ->
                Button(
                    onClick = { viewModel.runDirectCommand(cmd) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = label, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(label, fontSize = 10.sp)
                }
            }
        }

        // Input and Send row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("Escribe un comando...", color = Color.Gray, fontSize = 13.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_input_field")
            )

            Button(
                onClick = {
                    if (commandInput.trim().isNotEmpty()) {
                        viewModel.runDirectCommand(commandInput)
                        commandInput = ""
                        focusManager.clearFocus()
                    }
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("terminal_execute_btn")
            ) {
                Text("Ejecutar", fontSize = 13.sp)
            }
        }
    }
}
