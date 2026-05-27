package com.example

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import coil.compose.AsyncImage
import com.example.api.TargetLanguage
import com.example.api.SourceLanguage
import com.example.database.TranslationHistory
import com.example.services.OverlayService
import com.example.services.StorageManagerService
import com.example.ui.MangaTranslatorViewModel
import com.example.ui.TranslationUiState
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.TranslationPipeline
import com.example.utils.AppLogger
import com.example.utils.LogLevel
import com.example.utils.LogEntry
import java.io.InputStream
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MangaTranslatorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the storage manager service to periodically clean up images older than 7 days
        try {
            startService(Intent(this, StorageManagerService::class.java).apply {
                action = StorageManagerService.ACTION_START_CLEANUP
            })
            Log.d("MainActivity", "Successfully triggered StorageManagerService.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start StorageManagerService: ${e.message}", e)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F172A) // Sleek midnight background
                ) { innerPadding ->
                    val isOverlayRunning by OverlayService.isRunningState.collectAsState()
                    MangaTranslatorApp(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        onStartOverlay = { startOverlayService() },
                        onStopOverlay = { stopOverlayService() },
                        isOverlayRunning = isOverlayRunning
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == OverlayService.ACTION_CAPTURE_SCREEN) {
            Toast.makeText(this, "Yüzen Buton Tetiklendi! Çevirilecek Manga Ekran Görüntüsünü Seçin.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Toast permission guidance
            Toast.makeText(this, "Yüzen widget için 'Diğer uygulamaların üzerinde göster' izni gerekiyor.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            // Request push notifications on Android 13+ to ensure visual icon & notification are shown
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        102
                    )
                }
            }

            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_WIDGET
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Yüzen Çeviri Servisi Başlatıldı! Bildirim çubuğunda simgeyi görebilirsiniz.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_WIDGET
        }
        startService(intent)
        Toast.makeText(this, "Yüzen Çeviri Servisi Durduruldu.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MangaTranslatorApp(
    viewModel: MangaTranslatorViewModel,
    modifier: Modifier = Modifier,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    isOverlayRunning: Boolean
) {
    val context = LocalContext.current
    val history by viewModel.historyList.collectAsState()
    val uiState by viewModel.translationUiState.collectAsState()
    val selectedSourceLang by viewModel.selectedSourceLang.collectAsState()
    val fontSizeMultiplier by viewModel.fontSizeMultiplier.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val isDebugOverlayEnabled by viewModel.isDebugOverlayEnabled.collectAsState()
    val selectedTargetLang by viewModel.selectedTargetLang.collectAsState()
    val languagePacks by viewModel.languagePacks.collectAsState()

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Determine which language packs are needed for offline translation
    val neededPackCodes = remember(selectedSourceLang, selectedTargetLang) {
        val codes = mutableSetOf<String>()
        when (selectedSourceLang) {
            SourceLanguage.JAPANESE -> codes.add("ja")
            SourceLanguage.ENGLISH -> codes.add("en")
            SourceLanguage.AUTO_DETECT -> {
                codes.add("ja")
                codes.add("en")
            }
        }
        codes.add(selectedTargetLang.code)
        codes
    }

    val missingPacks = remember(languagePacks, neededPackCodes) {
        languagePacks.filter { pack ->
            neededPackCodes.contains(pack.code) && !pack.isDownloaded
        }
    }

    val autoOfflineFallbackAlert by viewModel.autoOfflineFallbackAlert.collectAsState()

    LaunchedEffect(autoOfflineFallbackAlert) {
        autoOfflineFallbackAlert?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.autoOfflineFallbackAlert.value = null
        }
    }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showActiveResultDialog by remember { mutableStateOf<TranslationUiState.Success?>(null) }
    var viewHistoryItem by remember { mutableStateOf<TranslationHistory?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }

    // Floating Overlay Toggle State Action Helper
    var localOverlayRunningState by remember { mutableStateOf(isOverlayRunning) }

    // Media and picker setups
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            try {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
                if (bitmap != null) {
                    viewModel.translateLocalBitmap(bitmap)
                } else {
                    Toast.makeText(context, "Görsel formatı desteklenmiyor!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Görsel yüklenirken hata oluştu!", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is TranslationUiState.Success) {
            showActiveResultDialog = uiState as TranslationUiState.Success
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // App Header Branding
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manga Çevirmeni",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00ADB5), // Vivid cyber cyan accent
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Yüzen Overlay & Gerçek Zamanlı Akıllı Manga/Webtoon Çevirici",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 16.sp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = { showLogsDialog = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ReceiptLong,
                            contentDescription = "Logları Görüntüle",
                            tint = Color(0xFF00ADB5),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Translate Logo",
                        tint = Color(0xFF00ADB5),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        // Active State Display Card (Dynamic)
        AnimatedVisibility(
            visible = uiState is TranslationUiState.Loading || uiState is TranslationUiState.Error,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            when (val state = uiState) {
                is TranslationUiState.Loading -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = BorderStroke(1.dp, Color(0xFF00ADB5)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF00ADB5),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Çeviri Yapılıyor...",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = state.step,
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
                is TranslationUiState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF451A1A)),
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
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error icon",
                                    tint = Color(0xFFF87171)
                                )
                                Text(
                                    text = "Bir Hata Meydana Geldi",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF87171),
                                    fontSize = 14.sp
                                )
                            }
                            Text(
                                text = state.message,
                                color = Color(0xFFFECACA),
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = { viewModel.resetState() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Kapat", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        // Overlay Widget Control Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Konuşma Balonları Yüzen Servis",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = "Diğer okuyucu ve web tarayıcı uygulamaların üzerinde dairesel yüzen bir tuş açar. Tıkladığınızda o anki ekranı çevirebilirsiniz.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!localOverlayRunningState) {
                        Button(
                            onClick = {
                                onStartOverlay()
                                localOverlayRunningState = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ADB5)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Servisi Başlat", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                onStopOverlay()
                                localOverlayRunningState = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Servisi Durdur", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Direct Picker Button
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        border = BorderStroke(1.dp, Color(0xFF00ADB5)),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00ADB5))
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Picker", tint = Color(0xFF00ADB5))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Galeri Çevirisi", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Translation Engine Configurations
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF00ADB5))
                    Text(
                        text = "Çeviri Altyapı Ayarları",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // API Key Panel Alert notice
                Surface(
                    color = Color(0xFF334155),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = "Key Icon",
                            tint = Color(0xFFE2E8F0),
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Gemini API Servis Anahtarı",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Bulut API anahtarınız AI Studio Sır Paneli (Secrets Panel) üzerinden otomatik aktarılır. Kod içerisine kesinlikle şifre yazılmaz.",
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Divider(color = Color(0xFF334155))

                // Source Language Configuration
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(
                            text = "Manga Orijinal Dili (OCR)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Manga dili veya otomatik tespit seçeneği",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SourceLanguage.values().forEach { lang ->
                            val isSelected = selectedSourceLang == lang
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF00ADB5) else Color(0xFF1E293B))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF00ADB5) else Color(0xFF334155),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        viewModel.selectedSourceLang.value = lang
                                    }
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val icon = when (lang) {
                                        SourceLanguage.JAPANESE -> "🇯🇵"
                                        SourceLanguage.ENGLISH -> "🇺🇸"
                                        SourceLanguage.AUTO_DETECT -> "🔍"
                                    }
                                    Text(
                                        text = icon,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = lang.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(color = Color(0xFF334155))

                // Target Language Configuration
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(
                            text = "Hedef Çeviri Dili",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Metinlerin çevrileceği hedef lisanı seçin",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetLanguage.values().forEach { language ->
                            val isSelected = selectedTargetLang == language
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF00ADB5) else Color(0xFF1E293B))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF00ADB5) else Color(0xFF334155),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        viewModel.selectedTargetLang.value = language
                                    }
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val flagIcon = when (language) {
                                        TargetLanguage.TURKISH -> "🇹🇷"
                                        TargetLanguage.ENGLISH -> "🇬🇧"
                                        TargetLanguage.GERMAN -> "🇩🇪"
                                    }
                                    Text(
                                        text = flagIcon,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = language.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(color = Color(0xFF334155))

                // API Key-less Mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "API Anahtarsız Çeviri Modu",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = if (isOfflineMode) "Aktif (Google ML Kit Cihaz İçi Çeviri - İnternetsiz)" else "Yapay Zeka Modu (Gemini Bulut API Anahtarı Gerektirir)",
                            fontSize = 11.sp,
                            color = if (isOfflineMode) Color(0xFF00ADB5) else Color(0xFF94A3B8)
                        )
                    }

                    Switch(
                        checked = isOfflineMode,
                        onCheckedChange = { checked ->
                            viewModel.isOfflineMode.value = checked
                            if (checked && missingPacks.isNotEmpty()) {
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                                Toast.makeText(context, "Bazı gerekli dil paketleri eksik! Lütfen indirin.", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00ADB5),
                            checkedTrackColor = Color(0xFF1E293B),
                            uncheckedThumbColor = Color(0xFFE2E8F0),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                if (isOfflineMode && missingPacks.isNotEmpty()) {
                    Surface(
                        color = Color(0xFF5A1C1C), // Deep warning crimson
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Eksik Paket Uyarısı",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Eksik Dil Paketleri Tespit Edildi!",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "Çevrimdışı modu kullanabilmek için seçtiğiniz kaynak/hedef dillere ait paketlerin indirilmesi gerekir. Eksik: " +
                                        missingPacks.joinToString(", ") { "${it.flag} ${it.name}" },
                                fontSize = 11.sp,
                                color = Color(0xFFFCA5A5),
                                lineHeight = 15.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Scroll down to manager
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            scrollState.animateScrollTo(scrollState.maxValue)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFDC2626),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Aşağı Git",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Paket Yöneticisine Git",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                
                                // Auto-download Button
                                Button(
                                    onClick = {
                                        missingPacks.forEach { pack ->
                                            viewModel.downloadLanguagePack(pack.code)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF10B981), // Emerald green
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Hepsini İndir",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Tümünü Tek Tıkla İndir",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                Divider(color = Color(0xFF334155))

                // OCR Debug Overlay Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "OCR Hata Ayıklama Katmanı",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = if (isDebugOverlayEnabled) "Aktif (Tespit edilen ve elenen tüm balonları görselde çerçeveler)" else "Kapalı (Sadece başarılı çevirileri gösterir)",
                            fontSize = 11.sp,
                            color = if (isDebugOverlayEnabled) Color(0xFF00ADB5) else Color(0xFF94A3B8)
                        )
                    }

                    Switch(
                        checked = isDebugOverlayEnabled,
                        onCheckedChange = { viewModel.isDebugOverlayEnabled.value = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00ADB5),
                            checkedTrackColor = Color(0xFF1E293B),
                            uncheckedThumbColor = Color(0xFFE2E8F0),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                Divider(color = Color(0xFF334155))

                // Font Expansion Sizer
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Çeviri Font Ölçekleyici",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "%.1fx".format(fontSizeMultiplier),
                            fontSize = 13.sp,
                            color = Color(0xFF00ADB5),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = fontSizeMultiplier,
                        onValueChange = { viewModel.fontSizeMultiplier.value = it },
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00ADB5),
                            activeTrackColor = Color(0xFF00ADB5),
                            inactiveTrackColor = Color(0xFF334155)
                        )
                    )
                }

                Divider(color = Color(0xFF334155))

                // Language Model Pack Manager section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Language Model Manager",
                            tint = Color(0xFF00ADB5),
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Cihaz İçi Dil Modelleri",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "İnternetsiz çevirimler için gerekli olan lisan paketleri",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languagePacks.forEach { pack ->
                            val isMissingNeededPack = isOfflineMode && missingPacks.any { it.code == pack.code }
                            val packBorder = if (isMissingNeededPack) {
                                BorderStroke(1.5.dp, Color(0xFFEF4444))
                            } else {
                                null
                            }
                            val packBackground = if (isMissingNeededPack) {
                                Color(0xFF2D1616) // Warning deep red background
                            } else {
                                Color(0xFF1E293B)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(packBackground)
                                    .then(if (packBorder != null) Modifier.border(packBorder, RoundedCornerShape(8.dp)) else Modifier)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = pack.flag,
                                        fontSize = 20.sp
                                    )
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = pack.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White
                                            )
                                            if (isMissingNeededPack) {
                                                Surface(
                                                    color = Color(0xFFDC2626),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Eksik Paket",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = pack.size,
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                            Text(
                                                text = "•",
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                            if (pack.isDownloading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(10.dp),
                                                    strokeWidth = 1.5.dp,
                                                    color = Color(0xFF00ADB5)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = "İşlem yapılıyor...",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF00ADB5),
                                                    fontWeight = FontWeight.Medium
                                                )
                                            } else if (pack.isDownloaded) {
                                                Text(
                                                    text = "İndirildi",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF00ADB5),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            } else {
                                                Text(
                                                    text = "İndirilmedi",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF94A3B8)
                                                )
                                            }
                                        }
                                        pack.error?.let { err ->
                                            Text(
                                                text = err,
                                                fontSize = 10.sp,
                                                color = Color.Red,
                                                lineHeight = 12.sp
                                            )
                                        }
                                    }
                                }

                                if (!pack.isDownloading) {
                                    if (pack.isDownloaded) {
                                        // Delete Button
                                        IconButton(
                                            onClick = { viewModel.deleteLanguagePack(pack.code) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "${pack.name} Sil",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else {
                                        // Download Button
                                        IconButton(
                                            onClick = { viewModel.downloadLanguagePack(pack.code) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "${pack.name} İndir",
                                                tint = Color(0xFF00ADB5),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Box(modifier = Modifier.size(36.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- CEVRIMDISI REHBER & HIZLI SOZLUK PANELI ---
        var quickInputText by remember { mutableStateOf("") }
        var quickTranslatedResult by remember { mutableStateOf("") }
        var isQuickTranslating by remember { mutableStateOf(false) }
        var quickSourceLang by remember { mutableStateOf(SourceLanguage.JAPANESE) }
        var quickTargetLang by remember { mutableStateOf(TargetLanguage.TURKISH) }

        val checkQuickMissing = remember(quickSourceLang, quickTargetLang, languagePacks) {
            val needed = mutableSetOf<String>()
            when (quickSourceLang) {
                SourceLanguage.JAPANESE -> needed.add("ja")
                SourceLanguage.ENGLISH -> needed.add("en")
                SourceLanguage.AUTO_DETECT -> {
                    needed.add("ja")
                    needed.add("en")
                }
            }
            needed.add(quickTargetLang.code)
            languagePacks.filter { needed.contains(it.code) && !it.isDownloaded }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = "Quick Dictionary",
                        tint = Color(0xFF00ADB5),
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Pratik Çevrimdışı Sözlük & Hızlı Çeviri",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Manga içi el yazısı veya kelimeleri anlık sorgulayın (100% Çevrimdışı)",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                // Selected Lang indicators and swap / toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Source selection
                    Box(modifier = Modifier.weight(1f)) {
                        var expandedSrc by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { expandedSrc = true },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Kaynak: ${quickSourceLang.displayName}",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = expandedSrc,
                            onDismissRequest = { expandedSrc = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            SourceLanguage.values().forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.displayName, color = Color.White) },
                                    onClick = {
                                        quickSourceLang = lang
                                        expandedSrc = false
                                    }
                                )
                            }
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Swap",
                        tint = Color(0xFF00ADB5),
                        modifier = Modifier.size(20.dp)
                    )

                    // Target selection
                    Box(modifier = Modifier.weight(1f)) {
                        var expandedTrg by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { expandedTrg = true },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Hedef: ${quickTargetLang.displayName}",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = expandedTrg,
                            onDismissRequest = { expandedTrg = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            TargetLanguage.values().forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.displayName, color = Color.White) },
                                    onClick = {
                                        quickTargetLang = lang
                                        expandedTrg = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (checkQuickMissing.isNotEmpty()) {
                    Surface(
                        color = Color(0xFF3B2512), // Warning orange/brown background for offline dictionary
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "⚠️ Eksik Dil Paketleri!",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDBA74)
                            )
                            Text(
                                text = "Sorgulama yapabilmek için ${checkQuickMissing.joinToString { it.name }} paketlerinin indirilmesi gerekir.",
                                fontSize = 10.sp,
                                color = Color(0xFFFFEDD5)
                            )
                            Button(
                                onClick = {
                                    checkQuickMissing.forEach { pack ->
                                        viewModel.downloadLanguagePack(pack.code)
                                    }
                                    coroutineScope.launch {
                                        scrollState.animateScrollTo(scrollState.maxValue)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.align(Alignment.End).height(24.dp)
                            ) {
                                Text("Paketleri İndir", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Input box
                OutlinedTextField(
                    value = quickInputText,
                    onValueChange = { quickInputText = it },
                    placeholder = { Text("Japonca veya İngilizce metin yazın/yapıştırın...", color = Color(0xFF64748B), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00ADB5),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 3,
                    trailingIcon = {
                        if (quickInputText.isNotEmpty()) {
                            IconButton(onClick = { quickInputText = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Temizle", tint = Color(0xFF64748B))
                            }
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${quickInputText.length} Karakter",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )

                    Button(
                        onClick = {
                            if (quickInputText.isBlank()) return@Button
                            isQuickTranslating = true
                            coroutineScope.launch {
                                val cleanText = quickInputText.trim()
                                try {
                                    val results = com.example.api.OnDeviceTranslator.translate(
                                        texts = listOf(cleanText),
                                        source = quickSourceLang,
                                        isActuallyJapanese = true,
                                        target = quickTargetLang
                                    )
                                    quickTranslatedResult = results.firstOrNull() ?: "[Hata: Sonuç Boş]"
                                } catch (e: Exception) {
                                    quickTranslatedResult = "[Çeviri Hatası: ${e.localizedMessage}]"
                                } finally {
                                    isQuickTranslating = false
                                }
                            }
                        },
                        enabled = quickInputText.isNotBlank() && checkQuickMissing.isEmpty() && !isQuickTranslating,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ADB5)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (isQuickTranslating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Çevriliyor...", fontSize = 12.sp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Translate, contentDescription = "Çevir", modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sözlükte Çevir", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                // Result Block representation
                if (quickTranslatedResult.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Çevrimdışı Çeviri Sonucu:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00ADB5)
                            )
                            IconButton(
                                onClick = { quickTranslatedResult = "" },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Kapat", tint = Color(0xFF64748B), modifier = Modifier.size(12.dp))
                            }
                        }
                        Text(
                            text = quickTranslatedResult,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // History Gallery Panel
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = "History", tint = Color(0xFF00ADB5))
                    Text(
                        text = "Geçmiş Çeviriler",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (history.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearAllHistory() }) {
                        Text("Tümünü Temizle", color = Color(0xFFEF4444), fontSize = 12.sp)
                    }
                }
            }

            if (history.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = "Empty",
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Henüz çevrilmiş manga sayfanız bulunmuyor.",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Yukarıdan Galeri Çevirisini başlatarak başlayın!",
                            color = Color(0xFF475569),
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp), // Fixed heights to work inside Scrollable container
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(history) { item ->
                        HistoryCardItem(
                            item = item,
                            onView = { viewHistoryItem = item },
                            onDelete = { viewModel.deleteHistory(item) }
                        )
                    }
                }
            }
        }
    }

    // Modal Results Display Sheet (Active Translation Pipeline Success Viewer)
    showActiveResultDialog?.let { success ->
        Dialog(
            onDismissRequest = {
                showActiveResultDialog = null
                viewModel.resetState()
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0F172A)
            ) {
                var showOriginal by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Header controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = {
                            showActiveResultDialog = null
                            viewModel.resetState()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Text(
                            text = "Çeviri Aracı",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        // Toggle button original vs translated
                        Button(
                            onClick = { showOriginal = !showOriginal },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showOriginal) Color(0xFFEF4444) else Color(0xFF00ADB5)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (showOriginal) "Orijinal" else "Çeviri",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }

                    // Main Image renderer with basic scaling zoom layout
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        ZoomableImage(
                            bitmap = if (showOriginal) success.result.originalBitmap else success.result.translatedBitmap
                        )
                        
                        // Small info badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Bulunan konuşma balonu: ${success.result.textBlocksCount}. Görseli kaydırmak veya büyütmek için yakınlaştırın.",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Detail Sheet for viewing historic items
    viewHistoryItem?.let { item ->
        Dialog(
            onDismissRequest = { viewHistoryItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0F172A)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { viewHistoryItem = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Text(
                            text = item.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )

                        IconButton(onClick = {
                            viewModel.deleteHistory(item)
                            viewHistoryItem = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                        }
                    }

                    // Display historic saved PNG image
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        ZoomableImage(imagePath = item.filePath)
                    }
                }
            }
        }
    }

    if (showLogsDialog) {
        SystemLogsDialog(onDismiss = { showLogsDialog = false })
    }
}

@Composable
fun HistoryCardItem(
    item: TranslationHistory,
    onView: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(Color(0xFF334155))
            ) {
                // Loading the local file inside internal storage
                AsyncImage(
                    model = item.filePath,
                    contentDescription = "Translated Manga Crop",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Tags labels overlay
                Surface(
                    color = Color(0xFF00ADB5),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = item.sourceLang,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${item.targetLang} Çevirisi",
                        color = Color(0xFF00ADB5),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Item",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * High-fidelity zoomable image component that supports gestures pinch to expand and drag.
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap? = null,
    imagePath: String? = null
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += offsetChange
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Simple double tap reset gestures support
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    }
                )
            }
            .transformable(state = state)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Translated zoom panel",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        } else if (imagePath != null) {
            AsyncImage(
                model = imagePath,
                contentDescription = "Translated zoom crop",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        }
    }
}

@Composable
fun SystemLogsDialog(onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    val logs by AppLogger.logs.collectAsState()
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val filteredLogs = remember(logs, searchQuery, selectedLevel) {
        logs.filter { entry ->
            val matchesSearch = entry.tag.contains(searchQuery, ignoreCase = true) ||
                    entry.message.contains(searchQuery, ignoreCase = true)
            val matchesLevel = selectedLevel == null || entry.level == selectedLevel
            matchesSearch && matchesLevel
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F172A)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Geri", tint = Color.White)
                    }

                    Text(
                        text = "Sistem Günlükleri (Log)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )

                    // Copy action button
                    IconButton(
                        onClick = {
                            if (filteredLogs.isEmpty()) {
                                Toast.makeText(context, "Kopyalanacak log yok!", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val textToCopy = filteredLogs.joinToString("\n") { entry ->
                                "[${entry.formatTime()}] [${entry.level}] [${entry.tag}]: ${entry.message}"
                            }
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                            Toast.makeText(context, "Loglar panoya kopyalandı!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Tümünü Kopyala", tint = Color(0xFF00ADB5))
                    }

                    // Trash Bin to clear
                    IconButton(
                        onClick = {
                            AppLogger.clear()
                            Toast.makeText(context, "Günlük temizlendi.", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Logları Temizle", tint = Color(0xFFEF4444))
                    }
                }

                // Controls & Filter Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF151F32))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search Text Box
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Loglarda ara veya filtrele...", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00ADB5),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Ara", tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Temizle", tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )

                    // Log Levels Pills Row
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // All Pill
                        FilterChip(
                            selected = selectedLevel == null,
                            onClick = { selectedLevel = null },
                            label = { Text("Hepsi", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF1E293B),
                                labelColor = Color(0xFF94A3B8),
                                selectedContainerColor = Color(0xFF00ADB5),
                                selectedLabelColor = Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = Color(0xFF334155),
                                selectedBorderColor = Color(0xFF00ADB5),
                                enabled = true,
                                selected = selectedLevel == null
                            )
                        )

                        LogLevel.values().forEach { level ->
                            val color = when (level) {
                                LogLevel.ERROR -> Color(0xFFEF4444)
                                LogLevel.WARN -> Color(0xFFF59E0B)
                                LogLevel.INFO -> Color(0xFF3B82F6)
                                LogLevel.DEBUG -> Color(0xFF10B981)
                            }
                            FilterChip(
                                selected = selectedLevel == level,
                                onClick = { selectedLevel = level },
                                label = { Text(level.name, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF1E293B),
                                    labelColor = color.copy(alpha = 0.8f),
                                    selectedContainerColor = color,
                                    selectedLabelColor = Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = Color(0xFF334155),
                                    selectedBorderColor = color,
                                    enabled = true,
                                    selected = selectedLevel == level
                                )
                            )
                        }
                    }
                }

                // Log Records List
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Inbox,
                                contentDescription = "Empty",
                                tint = Color(0xFF334155),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Arama kriterlerine uygun log bulunamadı.",
                                color = Color(0xFF64748B),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredLogs) { log ->
                            LogItemRow(log = log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: LogEntry) {
    val levelColor = when (log.level) {
        LogLevel.ERROR -> Color(0xFFEF4444)
        LogLevel.WARN -> Color(0xFFF59E0B)
        LogLevel.INFO -> Color(0xFF3B82F6)
        LogLevel.DEBUG -> Color(0xFF10B981)
    }

    val containerBg = when (log.level) {
        LogLevel.ERROR -> Color(0xFF2D1616)
        LogLevel.WARN -> Color(0xFF2D2316)
        else -> Color(0xFF1E293B)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (log.level == LogLevel.ERROR) Color(0xFF7F1D1D) else Color(0xFF334155))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left hand Level tag and Timestamps
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = levelColor,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = log.level.name,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = log.formatTime(),
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Medium
                    )
                }

                // Tag Label right hand
                Text(
                    text = log.tag,
                    fontSize = 10.sp,
                    color = Color(0xFF00ADB5),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Log Message Body
            Text(
                text = log.message,
                color = Color.White,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}
