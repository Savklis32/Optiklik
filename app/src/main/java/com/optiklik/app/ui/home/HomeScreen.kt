package com.optiklik.app.ui.home

import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.optiklik.app.data.local.dao.ExamSessionWithStats
import com.optiklik.app.data.preferences.TeacherProfile
import com.optiklik.app.data.repository.OptiklikRepository
import com.optiklik.app.service.FormTemplateExporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STANDARD_EXAM_PERIODS = listOf(
    "1. Dönem 1. Yazılı Yoklama",
    "1. Dönem 2. Yazılı Yoklama",
    "2. Dönem 1. Yazılı Yoklama",
    "2. Dönem 2. Yazılı Yoklama"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenStudentDirectory: () -> Unit,
    onOpenLiveScanner: (Long) -> Unit,
    onOpenBatchScan: (Long) -> Unit,
    onOpenResults: (Long) -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val directoryCount by viewModel.directoryCount.collectAsState()
    val teacherProfile by viewModel.teacherProfile.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showFormTemplatesDialog by remember { mutableStateOf(false) }
    var showOyssExtensionDialog by remember { mutableStateOf(false) }
    var showUserGuideDialog by remember { mutableStateOf(false) }
    var showLegalDialog by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<ExamSessionWithStats?>(null) }

    var selectedSchoolFilter by remember { mutableStateOf<String?>(null) }
    var selectedYearFilter by remember { mutableStateOf<String?>(null) }
    var selectedPeriodFilter by remember { mutableStateOf<String?>(null) }

    val distinctSchools = remember(sessions) {
        sessions.map { it.okulAdi.trim() }.filter { it.isNotBlank() }.distinct()
    }
    val distinctYears = remember(sessions) {
        sessions.map { it.egitimOgretimYili.trim() }.filter { it.isNotBlank() }.distinct()
    }
    val distinctPeriods = remember(sessions) {
        sessions.map { it.sinavDonemi.trim() }.filter { it.isNotBlank() }.distinct()
    }

    val filteredSessions = remember(
        sessions,
        selectedSchoolFilter,
        selectedYearFilter,
        selectedPeriodFilter
    ) {
        sessions.filter { s ->
            val matchSchool = selectedSchoolFilter == null || s.okulAdi == selectedSchoolFilter
            val matchYear = selectedYearFilter == null || s.egitimOgretimYili == selectedYearFilter
            val matchPeriod = selectedPeriodFilter == null || s.sinavDonemi == selectedPeriodFilter
            matchSchool && matchYear && matchPeriod
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Optiklik", fontWeight = FontWeight.Bold)
                        if (teacherProfile.isCompleted) {
                            Text(
                                "${teacherProfile.adSoyad} • ${teacherProfile.brans}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showFormTemplatesDialog = true }) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Formlar", maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = onOpenStudentDirectory) {
                        Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rehber ($directoryCount)", maxLines = 1, softWrap = false)
                    }
                    IconButton(onClick = { showProfileDialog = true }) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Öğretmen Profili",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Yeni Sınav (Yazılı)") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!teacherProfile.isCompleted) {
                item {
                    CompleteProfileBannerCard(onClick = { showProfileDialog = true })
                }
            }

            item {
                TeacherCockpitCard(
                    profile = teacherProfile,
                    directoryCount = directoryCount,
                    onEditProfile = { showProfileDialog = true },
                    onOpenDirectory = onOpenStudentDirectory
                )
            }

            item {
                OyssExtensionCard(onClick = { showOyssExtensionDialog = true })
            }

            item {
                UserGuideCard(onClick = { showUserGuideDialog = true })
            }

            item {
                LegalAndContactRow(
                    onOpenLegal = { showLegalDialog = true },
                    onOpenContact = { showContactDialog = true }
                )
            }

            if (sessions.isNotEmpty() && (distinctSchools.isNotEmpty() || distinctYears.isNotEmpty() || distinctPeriods.isNotEmpty())) {
                item {
                    SmartFilterSection(
                        schools = distinctSchools,
                        selectedSchool = selectedSchoolFilter,
                        onSelectSchool = { selectedSchoolFilter = it },
                        years = distinctYears,
                        selectedYear = selectedYearFilter,
                        onSelectYear = { selectedYearFilter = it },
                        periods = distinctPeriods,
                        selectedPeriod = selectedPeriodFilter,
                        onSelectPeriod = { selectedPeriodFilter = it }
                    )
                }
            }

            if (filteredSessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (sessions.isEmpty()) "Henüz sınav oturumu yok" else "Seçili filtrelere uygun sınav bulunamadı",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (sessions.isEmpty()) {
                                    "Sağ alttaki 'Yeni Sınav' butonuna dokunarak ilk sınav oturumunuzu oluşturun."
                                } else {
                                    "Filtre çiplerinden 'Tümü' seçeneğini seçerek tüm sınavları görebilirsiniz."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredSessions, key = { it.id }) { session ->
                    ExamSessionCard(
                        session = session,
                        onOpenLiveScanner = { onOpenLiveScanner(session.id) },
                        onOpenBatchScan = { onOpenBatchScan(session.id) },
                        onOpenResults = { onOpenResults(session.id) },
                        onDelete = { sessionToDelete = session }
                    )
                }
            }
        }
    }

    if (showProfileDialog) {
        TeacherProfileDialog(
            initialProfile = teacherProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { adSoyad, brans, yil, okullar ->
                viewModel.updateTeacherProfile(adSoyad, brans, yil, okullar)
                showProfileDialog = false
            }
        )
    }

    if (showCreateDialog) {
        CreateExamDialog(
            teacherProfile = teacherProfile,
            onDismiss = { showCreateDialog = false },
            onCreate = { okulAdi, yil, donem, sinif, ders, soruSayisi, soruPuanlari ->
                viewModel.createSession(
                    okulAdi = okulAdi,
                    egitimOgretimYili = yil,
                    sinavDonemi = donem,
                    sinifSubesi = sinif,
                    dersAdi = ders,
                    soruSayisi = soruSayisi,
                    soruPuanlari = soruPuanlari
                )
                showCreateDialog = false
            }
        )
    }

    sessionToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Sınavı Sil") },
            text = { Text("'${target.title}' sınavı ve tüm okunan sonuçları kalıcı olarak silinecek. Emin misiniz?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(target.id)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sil")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("İptal")
                }
            }
        )
    }

    if (showFormTemplatesDialog) {
        FormTemplatesDialog(
            onDismiss = { showFormTemplatesDialog = false },
            onDownload = { fileName, mimeType ->
                coroutineScope.launch {
                    val result = FormTemplateExporter.saveToDownloads(context, fileName, mimeType)
                    showFormTemplatesDialog = false
                    val msg = result.fold(
                        onSuccess = { savedName -> "İndirilenler klasörüne kaydedildi: $savedName" },
                        onFailure = { err -> "İndirme hatası: ${err.message ?: "Bilinmeyen hata"}" }
                    )
                    snackbarHostState.showSnackbar(msg)
                }
            },
            onShare = { fileName, mimeType, title ->
                coroutineScope.launch {
                    val result = FormTemplateExporter.createShareIntent(context, fileName, mimeType, title)
                    result.fold(
                        onSuccess = { chooserIntent ->
                            showFormTemplatesDialog = false
                            context.startActivity(chooserIntent)
                        },
                        onFailure = { err ->
                            showFormTemplatesDialog = false
                            snackbarHostState.showSnackbar("Paylaşım hatası: ${err.message ?: "Bilinmeyen hata"}")
                        }
                    )
                }
            }
        )
    }

    if (showOyssExtensionDialog) {
        OyssExtensionDialog(
            onDismiss = { showOyssExtensionDialog = false },
            onConfirmDownload = {
                coroutineScope.launch {
                    val result = FormTemplateExporter.exportOyssExtension(context)
                    showOyssExtensionDialog = false
                    val msg = result.fold(
                        onSuccess = { savedName -> "İndirilenler klasörüne kaydedildi: $savedName" },
                        onFailure = { err -> "İndirme hatası: ${err.message ?: "Bilinmeyen hata"}" }
                    )
                    snackbarHostState.showSnackbar(msg)
                }
            }
        )
    }

    if (showUserGuideDialog) {
        UserGuideDialog(onDismiss = { showUserGuideDialog = false })
    }

    if (showLegalDialog) {
        LegalNoticeDialog(onDismiss = { showLegalDialog = false })
    }

    if (showContactDialog) {
        ContactDialog(onDismiss = { showContactDialog = false })
    }
}

@Composable
private fun OyssExtensionCard(onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "E-OKUL OYSS NOT AKTARICI",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Excel notlarını e-Okul'a aktaran Chrome eklentisini indir ve kurulum adımlarını gör.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun UserGuideCard(onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Kullanım Kılavuzu",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Optik form oluşturma, tarama ve e-Okul aktarım adımlarını inceleyin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegalAndContactRow(
    onOpenLegal: () -> Unit,
    onOpenContact: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onOpenLegal,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Gavel, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Yasal Bildirim ve Gizlilik Politikası", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
            onClick = onOpenContact,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("İletişim", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun UserGuideDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kullanım Kılavuzu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Kapat")
                    }
                }
                HorizontalDivider()
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            loadUrl("file:///android_asset/kullanim_kilavuzu.html")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LegalNoticeDialog(onDismiss: () -> Unit) {
    val legalText = remember {
        """
YASAL BİLDİRİM VE GİZLİLİK POLİTİKASI

1. Veri Gizliliği ve Yerel Depolama
Optiklik uygulaması hiçbir şekilde kişisel veri, öğrenci bilgisi, sınav sonucu veya görüntü kaydı toplamaz, işlemez ve herhangi bir harici sunucuya aktarmaz. Uygulama tamamen çevrimdışı (offline) çalışır. Eklediğiniz öğretmen profili, öğrenci listeleri ve okuttuğunuz sınav sonuçları yalnızca kendi cihazınızın yerel depolama alanında saklanır.

2. İzinler
Uygulama, yalnızca optik formları tarayabilmek için Kamera iznini ve başarılı okuma bildirimi için Titreşim özelliğini kullanır. Çekilen veya taranan görüntüler cihaz dışına çıkarılmaz.

3. Sorumluluk Reddi
Optiklik, öğretmenlerin ölçme ve değerlendirme süreçlerini hızlandırmak amacıyla geliştirilmiş yardımcı bir araçtır. Optik okuma sonuçlarının doğruluğunu kontrol etmek, olası işaretleme hatalarını düzeltmek ve elde edilen notları resmî sistemlere (e-Okul vb.) aktarmak tamamen kullanıcının sorumluluğundadır. Uygulamanın kullanımından doğabilecek herhangi bir veri kaybı veya hatalı puan girişinden geliştirici sorumlu tutulamaz.
        """.trimIndent()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Yasal Bildirim ve Gizlilik Politikası",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = legalText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
private fun ContactDialog(onDismiss: () -> Unit) {
    val contactText = remember {
        """
Görüş, öneri ve destek talepleriniz için bizimle iletişime geçebilirsiniz:

E-posta: buraya mail adresiniz yazınız
        """.trimIndent()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("İletişim", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = contactText,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
private fun OyssExtensionDialog(
    onDismiss: () -> Unit,
    onConfirmDownload: () -> Unit
) {
    val instructions = remember {
        """
Telefonunuza indirdiğiniz E-OKUL_OYSS_NOT_AKTARICI isimli dosyayı bilgisayarınızın masaüstüne kopyalayınız.
Dosyayı rardan çıkarınız.

KURULUM
1) Dosyaları aynı klasörde tutun (manifest.json, content.js, xlsx.full.min.js).
2) Chrome'da chrome://extensions adresine gidin ve sağ üstten "Geliştirici modu"nu açın.
3) "Paketlenmemiş öğe yükle" deyip bu klasörü seçin (daha önce yüklüyse Yenile simgesine basın).

KULLANIM
1) Optiklik uygulamasından dışa aktardığınız Excel (.xlsx) dosyasını bilgisayarınıza alın.
2) e-Okul'da OYSS puan giriş ekranını (IOK10020 veya OOK10020) açıp sınıf/ders/sınavı getirin.
3) Sağ altta açılan "OYSS Excel Aktarıcı (Optiklik)" panelinden Excel dosyasını seçin.
4) Sırasıyla Kontrol et -> uyarıları inceleyin -> Aktar işlemlerini yapın.
5) Toplamları gözden geçirip KAYDET'e kendiniz basın (10 dk kayıt süresi vardır).

Not: Optiklik Excel'indeki Sıra No, T.C. Kimlik No, Sınıfı, Adı Soyadı, Toplam Puan ve kullanılmayan 0 puanlı soru sütunları otomatik filtrelenir; eşleştirme Okul No üzerinden yapılır.
        """.trimIndent()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("E-OKUL OYSS NOT AKTARICI", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = instructions,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirmDownload) {
                Text("TAMAM")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@Composable
private fun FormTemplatesDialog(
    onDismiss: () -> Unit,
    onDownload: (fileName: String, mimeType: String) -> Unit,
    onShare: (fileName: String, mimeType: String, title: String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Optik Form Şablonları", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FormTemplateItemCard(
                    title = "Optik Form 1 (Tekli Şablon — JPG)",
                    description = "Sınav kağıdı üstüne eklemek veya tekli baskı almak için standart 13 bloklu optik form.",
                    onDownload = { onDownload("Optik Form1.jpg", "image/jpeg") },
                    onShare = { onShare("Optik Form1.jpg", "image/jpeg", "Optik Form 1 Paylaş") }
                )
                FormTemplateItemCard(
                    title = "Optik Form 2 (2'li A4 Şablon — PDF)",
                    description = "Tek A4 sayfasında 2 adet optik form. Yazdırdıktan sonra ortadan keserek tek form halinde taratınız.",
                    onDownload = { onDownload("Optik Form2.pdf", "application/pdf") },
                    onShare = { onShare("Optik Form2.pdf", "application/pdf", "Optik Form 2 Paylaş") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

@Composable
private fun FormTemplateItemCard(
    title: String,
    description: String,
    onDownload: () -> Unit,
    onShare: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("İndir", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = onShare,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paylaş", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun CompleteProfileBannerCard(onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Öğretmen Profilini Tamamla",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    "Adınızı, branşınızı ve görev yaptığınız okulları ekleyerek sınav ve Excel çıktılarını otomatikleştirin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun TeacherCockpitCard(
    profile: TeacherProfile,
    directoryCount: Int,
    onEditProfile: () -> Unit,
    onOpenDirectory: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.adSoyad.ifBlank { "Öğretmen Bilgisi Girilmedi" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (profile.brans.isNotBlank()) {
                            "${profile.brans} • ${profile.effectiveYear()}"
                        } else {
                            "Branş belirtilmedi"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onEditProfile) {
                    Text("Profili Düzenle", maxLines = 1, softWrap = false)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatChip(
                    label = "Kayıtlı Okul",
                    value = "${profile.okullar.size}",
                    modifier = Modifier.weight(1f)
                )
                StatChip(
                    label = "Rehberdeki Öğrenci",
                    value = "$directoryCount",
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenDirectory)
                )
            }
        }
    }
}

@Composable
private fun SmartFilterSection(
    schools: List<String>,
    selectedSchool: String?,
    onSelectSchool: (String?) -> Unit,
    years: List<String>,
    selectedYear: String?,
    onSelectYear: (String?) -> Unit,
    periods: List<String>,
    selectedPeriod: String?,
    onSelectPeriod: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (schools.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = selectedSchool == null,
                        onClick = { onSelectSchool(null) },
                        label = { Text("Tüm Okullar", maxLines = 1, softWrap = false) }
                    )
                }
                items(schools) { school ->
                    FilterChip(
                        selected = selectedSchool == school,
                        onClick = { onSelectSchool(if (selectedSchool == school) null else school) },
                        label = { Text(school, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }
        if (years.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = selectedYear == null,
                        onClick = { onSelectYear(null) },
                        label = { Text("Tüm Yıllar", maxLines = 1, softWrap = false) }
                    )
                }
                items(years) { year ->
                    FilterChip(
                        selected = selectedYear == year,
                        onClick = { onSelectYear(if (selectedYear == year) null else year) },
                        label = { Text(year, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }
        if (periods.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = selectedPeriod == null,
                        onClick = { onSelectPeriod(null) },
                        label = { Text("Tüm Dönemler", maxLines = 1, softWrap = false) }
                    )
                }
                items(periods) { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { onSelectPeriod(if (selectedPeriod == period) null else period) },
                        label = { Text(period, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeacherProfileDialog(
    initialProfile: TeacherProfile,
    onDismiss: () -> Unit,
    onSave: (adSoyad: String, brans: String, varsayilanYil: String, okullar: List<String>) -> Unit
) {
    var adSoyad by remember { mutableStateOf(initialProfile.adSoyad) }
    var brans by remember { mutableStateOf(initialProfile.brans) }
    var varsayilanYil by remember { mutableStateOf(initialProfile.varsayilanYil) }
    var okullar by remember { mutableStateOf(initialProfile.okullar) }
    var newSchoolInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Öğretmen Profili") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = adSoyad,
                    onValueChange = { adSoyad = it },
                    label = { Text("Adı Soyadı") },
                    placeholder = { Text("Örn: Ahmet Yılmaz") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = brans,
                    onValueChange = { brans = it },
                    label = { Text("Branşı") },
                    placeholder = { Text("Örn: Coğrafya, Matematik") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = varsayilanYil,
                    onValueChange = { varsayilanYil = it },
                    label = { Text("Eğitim Öğretim Yılı") },
                    placeholder = { Text("Örn: 2026-2027") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Görev Yaptığı Okullar",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (okullar.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        okullar.forEach { school ->
                            InputChip(
                                selected = false,
                                onClick = { okullar = okullar.filterNot { it == school } },
                                label = { Text(school, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Kaldır",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newSchoolInput,
                        onValueChange = { newSchoolInput = it },
                        label = { Text("Okul Ekle") },
                        placeholder = { Text("Okul adı yazın") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            val clean = newSchoolInput.trim()
                            if (clean.isNotBlank() && okullar.none { it.equals(clean, ignoreCase = true) }) {
                                okullar = okullar + clean
                            }
                            newSchoolInput = ""
                        },
                        enabled = newSchoolInput.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Ekle")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalSchools = if (newSchoolInput.isNotBlank() &&
                        okullar.none { it.equals(newSchoolInput.trim(), ignoreCase = true) }
                    ) {
                        okullar + newSchoolInput.trim()
                    } else {
                        okullar
                    }
                    onSave(adSoyad, brans, varsayilanYil, finalSchools)
                },
                enabled = adSoyad.isNotBlank() && brans.isNotBlank()
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateExamDialog(
    teacherProfile: TeacherProfile,
    onDismiss: () -> Unit,
    onCreate: (
        okulAdi: String,
        yil: String,
        donem: String,
        sinif: String,
        ders: String,
        soruSayisi: Int,
        soruPuanlari: String
    ) -> Unit
) {
    var okulAdi by remember { mutableStateOf(teacherProfile.okullar.firstOrNull().orEmpty()) }
    var egitimOgretimYili by remember { mutableStateOf("") }
    var sinavDonemi by remember { mutableStateOf(STANDARD_EXAM_PERIODS.first()) }
    var sinifSubesi by remember { mutableStateOf("") }
    var dersAdi by remember { mutableStateOf("") }
    var soruSayisi by remember { mutableIntStateOf(5) }
    val questionPoints = remember {
        mutableStateListOf<String>().apply {
            repeat(5) { add("20") }
        }
    }

    fun redistributePoints(count: Int) {
        soruSayisi = count
        questionPoints.clear()
        val base = 100 / count
        val rem = 100 % count
        for (i in 0 until count) {
            questionPoints.add((base + if (i < rem) 1 else 0).toString())
        }
    }

    val totalPoints = questionPoints.sumOf { it.toIntOrNull() ?: 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni Sınav Oturumu") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Okul Adı
                Text("1. Okul Adı", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (teacherProfile.okullar.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        teacherProfile.okullar.forEach { school ->
                            FilterChip(
                                selected = okulAdi == school,
                                onClick = { okulAdi = school },
                                label = { Text(school, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = okulAdi,
                    onValueChange = { okulAdi = it },
                    label = { Text("Okul Adı") },
                    placeholder = { Text("Örn: Atatürk Anadolu Lisesi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Eğitim Öğretim Yılı
                OutlinedTextField(
                    value = egitimOgretimYili,
                    onValueChange = { egitimOgretimYili = it },
                    label = { Text("2. Eğitim Öğretim Yılı") },
                    placeholder = { Text("Örn: 2026-2027") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. Sınav Dönemi (2x2 Grid)
                Text("3. Sınav Dönemi", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (rowIdx in 0 until 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (colIdx in 0 until 2) {
                                val option = STANDARD_EXAM_PERIODS[rowIdx * 2 + colIdx]
                                FilterChip(
                                    selected = sinavDonemi == option,
                                    onClick = { sinavDonemi = option },
                                    label = {
                                        Text(
                                            text = option,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // 4. Sınıf / Şube
                OutlinedTextField(
                    value = sinifSubesi,
                    onValueChange = { sinifSubesi = it },
                    label = { Text("4. Sınıf / Şube") },
                    placeholder = { Text("Örn: 9-A, 10-B") },
                    supportingText = {
                        if (sinifSubesi.isNotBlank()) {
                            Text("Standart: ${OptiklikRepository.normalizeClassBranch(sinifSubesi)}")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 5. Ders Adı
                OutlinedTextField(
                    value = dersAdi,
                    onValueChange = { dersAdi = it },
                    label = { Text("5. Ders Adı") },
                    placeholder = { Text("Örn: Coğrafya, Matematik") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 6. Soru Sayısı ve Puan Dağılımı
                Text(
                    "6. Soru Sayısı ve Puan Dağılımı",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (rowStart in listOf(1, 6)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (num in rowStart until (rowStart + 5)) {
                                FilterChip(
                                    selected = soruSayisi == num,
                                    onClick = { redistributePoints(num) },
                                    label = {
                                        Text(
                                            text = "$num",
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Question point inputs (rows of 5)
                val chunks = (0 until soruSayisi).chunked(5)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chunks.forEach { rowIndices ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowIndices.forEach { idx ->
                                OutlinedTextField(
                                    value = questionPoints[idx],
                                    onValueChange = { input ->
                                        questionPoints[idx] = input.filter { it.isDigit() }.take(2)
                                    },
                                    label = { Text("S${idx + 1}", maxLines = 1) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(5 - rowIndices.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                val isTotalValid = totalPoints == 100
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isTotalValid) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isTotalValid) {
                            "Toplam Puan: 100 / 100"
                        } else {
                            "Toplam puan 100 olmalıdır (Şu an: $totalPoints)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isTotalValid) Color(0xFF166534) else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pointsCsv = questionPoints.joinToString(",") {
                        (it.toIntOrNull() ?: 0).coerceIn(0, 99).toString()
                    }
                    onCreate(
                        okulAdi,
                        egitimOgretimYili,
                        sinavDonemi,
                        sinifSubesi,
                        dersAdi,
                        soruSayisi,
                        pointsCsv
                    )
                },
                enabled = sinavDonemi.isNotBlank() &&
                    sinifSubesi.isNotBlank() &&
                    dersAdi.isNotBlank() &&
                    totalPoints == 100
            ) {
                Text("Oluştur")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@Composable
private fun ExamSessionCard(
    session: ExamSessionWithStats,
    onOpenLiveScanner: () -> Unit,
    onOpenBatchScan: () -> Unit,
    onOpenResults: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr", "TR")) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (session.okulAdi.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.School,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "${session.okulAdi} • ${session.egitimOgretimYili}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (session.sinifSubesi.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = session.sinifSubesi,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = session.sinavDonemi,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = session.dersAdi.ifBlank { session.title },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dateFormat.format(Date(session.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Sil",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatChip(
                    label = "Okunan Kağıt",
                    value = "${session.paperCount}",
                    modifier = Modifier.weight(1f)
                )
                StatChip(
                    label = "Sınıf Ortalaması",
                    value = String.format(Locale("tr", "TR"), "%.1f", session.averageScore),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenLiveScanner,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kamera", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onOpenBatchScan,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Dosya/PDF", maxLines = 1, softWrap = false)
                }
                FilledTonalButton(
                    onClick = onOpenResults,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sonuçlar", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
