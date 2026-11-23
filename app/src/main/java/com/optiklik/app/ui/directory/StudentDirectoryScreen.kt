package com.optiklik.app.ui.directory

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import com.optiklik.app.data.repository.OptiklikRepository
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudentDirectoryScreen(
    viewModel: StudentDirectoryViewModel,
    onBack: () -> Unit
) {
    val students by viewModel.students.collectAsState()
    val teacherProfile by viewModel.teacherProfile.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences("optiklik_teacher_profile", Context.MODE_PRIVATE)
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedSchoolFilter by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var showEokulGuideDialog by remember { mutableStateOf(false) }
    var dontShowEokulGuideAgain by remember { mutableStateOf(false) }
    var editingStudent by remember { mutableStateOf<StudentDirectoryEntity?>(null) }
    var studentToDelete by remember { mutableStateOf<StudentDirectoryEntity?>(null) }
    var schoolToClear by remember { mutableStateOf<String?>(null) }

    val availableSchools = remember(teacherProfile.okullar, students) {
        (teacherProfile.okullar + students.map { it.okulAdi })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    val filteredStudents = remember(students, searchQuery, selectedSchoolFilter) {
        val q = searchQuery.trim().lowercase(Locale("tr", "TR"))
        students.filter { st ->
            val matchesSchool = selectedSchoolFilter == null ||
                st.okulAdi.equals(selectedSchoolFilter, ignoreCase = true)
            val matchesQuery = q.isEmpty() ||
                st.adSoyad.lowercase(Locale("tr", "TR")).contains(q) ||
                st.okulNo.lowercase(Locale("tr", "TR")).contains(q) ||
                st.tcKimlikNo.contains(q) ||
                st.sinifSubesi.lowercase(Locale("tr", "TR")).contains(q) ||
                st.okulAdi.lowercase(Locale("tr", "TR")).contains(q)
            matchesSchool && matchesQuery
        }
    }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    val launchDirectoryPicker = {
        directoryPicker.launch(
            arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf",
                "text/csv",
                "text/comma-separated-values",
                "*/*"
            )
        )
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
                        Text("Öğrenci Rehberi", fontWeight = FontWeight.Bold)
                        Text(
                            "Toplam ${students.size} öğrenci • ${availableSchools.size} okul",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val hideGuide = prefs.getBoolean("hide_eokul_import_guide", false)
                            if (hideGuide) {
                                launchDirectoryPicker()
                            } else {
                                dontShowEokulGuideAgain = false
                                showEokulGuideDialog = true
                            }
                        },
                        enabled = !isImporting
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Liste Yükle", maxLines = 1, softWrap = false)
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
                onClick = {
                    editingStudent = null
                    showAddEditDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Öğrenci Ekle") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Ad Soyad, Okul No, Sınıf veya T.C. ara...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Temizle")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (availableSchools.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedSchoolFilter == null,
                                onClick = { selectedSchoolFilter = null },
                                label = { Text("Tüm Okullar (${students.size})", maxLines = 1) }
                            )
                        }
                        items(availableSchools) { school ->
                            val countForSchool = students.count {
                                it.okulAdi.equals(school, ignoreCase = true)
                            }
                            FilterChip(
                                selected = selectedSchoolFilter == school,
                                onClick = {
                                    selectedSchoolFilter = if (selectedSchoolFilter == school) null else school
                                },
                                label = { Text("$school ($countForSchool)", maxLines = 1) }
                            )
                        }
                    }
                    selectedSchoolFilter?.let { activeSchool ->
                        OutlinedButton(
                            onClick = { schoolToClear = activeSchool },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("'$activeSchool' Listesini Temizle", maxLines = 1)
                        }
                    }
                }
            }

            if (filteredStudents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (students.isEmpty()) "Rehberde henüz öğrenci yok" else "Aramaya uygun öğrenci bulunamadı",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Sağ üstten e-Okul (.XLS/.XLSX/.PDF) listesi yükleyebilir veya '+ Öğrenci Ekle' ile tekil kayıt ekleyebilirsiniz.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredStudents, key = { "${it.tcKimlikNo}_${it.okulAdi}" }) { student ->
                        StudentDirectoryCard(
                            student = student,
                            onClick = {
                                editingStudent = student
                                showAddEditDialog = true
                            },
                            onDelete = { studentToDelete = student }
                        )
                    }
                }
            }
        }
    }

    // "Bu Liste Hangi Okula Ait?" school & class selection dialog before parsing/merging
    pendingImportUri?.let { uri ->
        var selectedSchool by remember(uri) {
            mutableStateOf(availableSchools.firstOrNull() ?: "")
        }
        var sinifSubesi by remember(uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Bu Liste Hangi Okula Ait?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Yüklenen öğrenciler seçtiğiniz okul ve sınıfa atanacak, mevcut listeler silinmeden birleştirilecektir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (availableSchools.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableSchools.forEach { school ->
                                FilterChip(
                                    selected = selectedSchool == school,
                                    onClick = { selectedSchool = school },
                                    label = { Text(school, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = selectedSchool,
                        onValueChange = { selectedSchool = it },
                        label = { Text("Okul Adı") },
                        placeholder = { Text("Örn: Atatürk Anadolu Lisesi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sinifSubesi,
                        onValueChange = { sinifSubesi = it },
                        label = { Text("Sınıf / Şube") },
                        placeholder = { Text("Örn: 9-A, 10-B") },
                        supportingText = {
                            if (sinifSubesi.isNotBlank()) {
                                Text("Standart: ${OptiklikRepository.normalizeClassBranch(sinifSubesi)}")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importStudentsForSchool(uri, selectedSchool, sinifSubesi)
                        pendingImportUri = null
                    },
                    enabled = selectedSchool.isNotBlank() && sinifSubesi.isNotBlank()
                ) {
                    Text("Yükle ve Birleştir")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text("İptal")
                }
            }
        )
    }

    // Manual Add / Edit student dialog
    if (showAddEditDialog) {
        val original = editingStudent
        var tc by remember(original) { mutableStateOf(original?.tcKimlikNo ?: "") }
        var okulAdi by remember(original) {
            mutableStateOf(original?.okulAdi?.ifBlank { availableSchools.firstOrNull().orEmpty() } ?: availableSchools.firstOrNull().orEmpty())
        }
        var sinifSubesi by remember(original) { mutableStateOf(original?.sinifSubesi ?: "") }
        var okulNo by remember(original) { mutableStateOf(original?.okulNo ?: "") }
        var adSoyad by remember(original) { mutableStateOf(original?.adSoyad ?: "") }

        AlertDialog(
            onDismissRequest = { showAddEditDialog = false },
            title = { Text(if (original == null) "Manuel Öğrenci Ekle" else "Öğrenci Düzenle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (availableSchools.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            availableSchools.forEach { school ->
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
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sinifSubesi,
                            onValueChange = { sinifSubesi = it },
                            label = { Text("Sınıf / Şube") },
                            placeholder = { Text("9-A") },
                            supportingText = {
                                if (sinifSubesi.isNotBlank()) {
                                    Text(OptiklikRepository.normalizeClassBranch(sinifSubesi))
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = okulNo,
                            onValueChange = { okulNo = it.filter { c -> c.isDigit() } },
                            label = { Text("Okul No") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = tc,
                        onValueChange = { tc = it.filter { c -> c.isDigit() }.take(11) },
                        label = { Text("T.C. Kimlik No (11 hane)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adSoyad,
                        onValueChange = { adSoyad = it },
                        label = { Text("Adı Soyadı") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveManualStudent(
                            tcKimlikNo = tc,
                            okulAdi = okulAdi,
                            sinifSubesi = sinifSubesi,
                            okulNo = okulNo,
                            adSoyad = adSoyad,
                            originalStudent = original
                        )
                        showAddEditDialog = false
                    },
                    enabled = tc.length == 11 && adSoyad.isNotBlank()
                ) {
                    Text("Kaydet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEditDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }

    studentToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { studentToDelete = null },
            title = { Text("Öğrenciyi Sil") },
            text = { Text("'${target.adSoyad}' kaydı rehberden silinecek. Emin misiniz?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteStudent(target)
                        studentToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sil")
                }
            },
            dismissButton = {
                TextButton(onClick = { studentToDelete = null }) {
                    Text("İptal")
                }
            }
        )
    }

    schoolToClear?.let { targetSchool ->
        AlertDialog(
            onDismissRequest = { schoolToClear = null },
            title = { Text("Okul Listesini Temizle") },
            text = {
                Text("'$targetSchool' okuluna kayıtlı tüm öğrenciler rehberden kalıcı olarak silinecek. Diğer okullar etkilenmeyecek. Emin misiniz?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSchoolStudents(targetSchool)
                        selectedSchoolFilter = null
                        schoolToClear = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Temizle")
                }
            },
            dismissButton = {
                TextButton(onClick = { schoolToClear = null }) {
                    Text("İptal")
                }
            }
        )
    }

    if (showEokulGuideDialog) {
        val guideSteps = remember {
            buildAnnotatedString {
                fun appendBold(text: String) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
                }
                append("1. e-OKUL'a giriş yapın.\n")
                append("2. Ortaöğretim Öğrenci İşlemleri'ne tıklayın.\n")
                append("3. Yazdır (Raporlar) simgesine tıklayın.\n")
                append("4. Açılan sayfada en üstte bulunan seçeneklerden ")
                appendBold("Gösterici Seçenekleri (Html5 Word Excel)")
                append(" seçeneğini seçin.\n")
                append("5. ")
                appendBold("OOG01001R090 Fotoğraflı-Kimlik Bilgili Öğrenci Listesi")
                append(" raporuna tıklayın.\n")
                append("6. Açılan pencerede ")
                appendBold("Sınıfı")
                append(" ve ")
                appendBold("Şubesi")
                append(" seçiniz, sonra ")
                appendBold("TAMAM")
                append(" yazısına tıklayınız.\n")
                append("7. Yazıcı işareti yanında bulunan ")
                appendBold(">>")
                append(" işaretine tıklayarak ")
                appendBold("Excel (Sadece Veri)")
                append(" üzerine tıklayıp sınıf öğrenci listesini indiriniz.\n")
                append("8. İndirdiğiniz sınıf listesini uygulama içerisindeki ")
                appendBold("Öğrenci Listesi Yükleme")
                append(" alanından yükleyiniz.")
            }
        }
        AlertDialog(
            onDismissRequest = { showEokulGuideDialog = false },
            title = { Text("e-Okul Öğrenci Listesi Nasıl İndirilir?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = guideSteps,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontShowEokulGuideAgain = !dontShowEokulGuideAgain }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontShowEokulGuideAgain,
                            onCheckedChange = { dontShowEokulGuideAgain = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Bir daha gösterme",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dontShowEokulGuideAgain) {
                            prefs.edit().putBoolean("hide_eokul_import_guide", true).apply()
                        }
                        showEokulGuideDialog = false
                        launchDirectoryPicker()
                    }
                ) {
                    Text("TAMAM")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEokulGuideDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }
}

@Composable
private fun StudentDirectoryCard(
    student: StudentDirectoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (student.okulAdi.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.School,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = student.okulAdi,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (student.sinifSubesi.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = student.sinifSubesi,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = student.adSoyad,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Okul No: ${student.okulNo} • T.C: ${student.tcKimlikNo}",
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
    }
}
