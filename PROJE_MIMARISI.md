# Optiklik — Proje Mimarisi ve Teknik Özet

Optiklik; Android (Kotlin, Jetpack Compose, CameraX, OpenCV, Room) tabanlı, **Yazılı Yoklama (`WRITTEN`)** ve **Çoktan Seçmeli Test (`TEST`)** optik formlarını canlı kamera, tek çekim veya toplu galeri/PDF üzerinden okuyan bir optik form değerlendirme uygulamasıdır.

---

## 1. Klasör ve Dosya Ağacı

```text
Optiklik/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   ├── kullanimkilavuzu.html
        │   │   ├── eklenti/E-OKUL_OYSS_NOT_AKTARICI.rar
        │   │   └── formlar/ (Optik Form1..4 jpg/pdf)
        │   ├── res/values/ (strings.xml, themes.xml)
        │   └── java/com/optiklik/app/
        │       ├── MainActivity.kt
        │       ├── OptiklikApp.kt
        │       ├── data/
        │       │   ├── local/
        │       │   │   ├── OptiklikDatabase.kt
        │       │   │   ├── dao/ (ExamResultDao.kt, ExamSessionDao.kt, StudentDirectoryDao.kt)
        │       │   │   └── entity/ (ExamResultEntity.kt, ExamSessionEntity.kt, StudentDirectoryEntity.kt)
        │       │   ├── preferences/TeacherProfilePreferences.kt
        │       │   └── repository/OptiklikRepository.kt
        │       ├── domain/omr/
        │       │   ├── OmrModels.kt
        │       │   ├── OmrProcessor.kt
        │       │   ├── TestOmrProcessor.kt
        │       │   └── TcKimlikValidator.kt
        │       ├── service/
        │       │   ├── BatchScannerService.kt
        │       │   ├── ExcelExporter.kt
        │       │   ├── FormTemplateExporter.kt
        │       │   └── StudentListParser.kt
        │       └── ui/
        │           ├── batch/ (BatchScanScreen.kt, BatchScanViewModel.kt)
        │           ├── components/ScoreBadges.kt
        │           ├── directory/ (StudentDirectoryScreen.kt, StudentDirectoryViewModel.kt)
        │           ├── home/ (HomeScreen.kt, HomeViewModel.kt)
        │           ├── navigation/NavGraph.kt
        │           ├── results/ (ExamResultsScreen.kt, ExamResultsViewModel.kt)
        │           ├── scanner/ (LiveScannerScreen.kt, LiveScannerViewModel.kt)
        │           └── theme/Theme.kt
        └── test/
            ├── resources/omr_expected_fixtures.json
            └── java/com/optiklik/app/
                ├── data/local/MigrationTest.kt
                ├── data/repository/OptiklikRepositoryTest.kt
                ├── domain/TurkishLocaleTest.kt
                ├── domain/omr/ (OmrFixtureTest.kt, TcKimlikValidatorTest.kt)
                ├── service/ (ExifOrientationTest.kt, StudentListParserTest.kt)
                └── ui/home/HomeViewModelTest.kt
```

---

## 2. Dosya Görev Özetleri

- **Giriş & Navigasyon:**
  - `OptiklikApp.kt`: OpenCV başlatma, Room veritabanı, `TeacherProfilePreferences` ve `OptiklikRepository` singleton kurulumu.
  - `MainActivity.kt`: Compose ana aktivitesi; `OptiklikTheme` ve `OptiklikNavGraph` barındırır.
  - `NavGraph.kt`: `home`, `student_directory`, `live_scan/{sessionId}`, `batch_scan/{sessionId}`, `results/{sessionId}` rotalarını yönetir.
- **Veri Katmanı (Room & Preferences):**
  - `OptiklikDatabase.kt`: `exam_sessions`, `exam_results`, `student_directory` tabloları ve sürüm geçişleri (migrations).
  - `ExamSessionEntity.kt`: Sınav oturumu tanımı (`examType`: `WRITTEN` veya `TEST`, `soruSayisi`, `soruPuanlari`, `cevapAnahtari`).
  - `ExamResultEntity.kt`: Okunan öğrenci sonucu, 10 sütunluk yazılı puanları, `scoresJson`, `studentAnswersJson`, `ReviewReason` listesi.
  - `StudentDirectoryEntity.kt`: `(tcKimlikNo, okulAdi)` birincil anahtarlı öğrenci rehberi.
  - `OptiklikRepository.kt`: Öğrenci eşleştirme (T.C. / Okul No), mükerrer kontrolü, sınıf uyuşmazlığı ve manuel inceleme bayraklarını yönetir.
  - `TeacherProfilePreferences.kt`: `SharedPreferences` (`optiklik_teacher_profile`) üzerinde öğretmen profili ve hata ayıklama modunu tutar.
- **OMR Motorları & Doğrulama:**
  - `OmrModels.kt`: `GuidanceState` (kadraj/netlik/köşe yönlendirmeleri), `OmrReadResult`, `OmrFrameAnalysis` modelleri.
  - `OmrProcessor.kt`: **Yazılı Yoklama (`WRITTEN`)** formları için yatay form OMR motoru (T.C., Okul No, 1–10 soru puanı ve kodlu toplam okuma).
  - `TestOmrProcessor.kt`: **Çoktan Seçmeli Test (`TEST`)** formları için dikey form OMR motoru; siyah kare köşe işaretçisi tespiti (`detectSquareCornerMarkers`), sabit `OpticalFormLayout` ve First-Large-Gap dinamik eşikleme ile 1–25 soru şık okuma ve cevap anahtarı puanlaması.
  - `TcKimlikValidator.kt`: 11 haneli T.C. Kimlik Numarası algoritmik doğrulaması.
- **Servisler:**
  - `BatchScannerService.kt`: Galeri görselleri ve çok sayfalı PDF dosyalarından toplu tarama (EXIF yön düzeltme dahil; her PDF sayfasını tek form olarak tam sayfa okur).
  - `ExcelExporter.kt`: Sonuçları `.xlsx` / `.csv` formatında dışa aktarma.
  - `FormTemplateExporter.kt`: Hazır optik form şablonlarını (`assets/formlar/`) paylaşma/kaydetme.
  - `StudentListParser.kt`: e-Okul / Excel / CSV öğrenci listelerini ayrıştırma.
- **Ekranlar & ViewModel'ler:**
  - `HomeScreen.kt` / `HomeViewModel.kt`: Sınav oturumları listesi, yeni sınav oluşturma (Yazılı / Test, cevap anahtarı, soru puanları), öğretmen profili ve kullanım kılavuzu (`kullanimkilavuzu.html`).
  - `LiveScannerScreen.kt` / `LiveScannerViewModel.kt`: CameraX canlı önizleme, `ImageCapture` yüksek çözünürlüklü çekim (`TEST` modu), flaş (Torch), dokunarak odaklama (Tap-to-Focus), `cropTestGuideRoi` (`%82 × %84`) ROI kırpması, yeşil köşe artı (+) göstergesi, otomatik/manuel çekim ve anlık kayıt.
  - `BatchScanScreen.kt` / `BatchScanViewModel.kt`: Toplu fotoğraf/PDF tarama kuyruğu ve ilerleme takibi.
  - `ExamResultsScreen.kt` / `ExamResultsViewModel.kt`: Sınav sonuç tablosu, hata ayıklama (debug) görselleri, hatalı T.C./puan düzeltme ve Excel dışa aktarma.
  - `StudentDirectoryScreen.kt` / `StudentDirectoryViewModel.kt`: Okul bazlı öğrenci rehberi yönetimi ve dosya içe aktarma.

---

## 3. OMR Okuma, Köşe Tespiti ve Değerlendirme Kuralları

### A. Yazılı (`WRITTEN` — `OmrProcessor.kt`)
- Yatay form düzeninde 4 köşe işaretçisi aranır; perspektif düzeltmesi (canonical warp) sonrası T.C. Kimlik (11 hane), Okul No, 10 soruya kadar puan blokları ve kodlanmış toplam puan okunur.
- Hesaplanan toplam ile kodlanan toplam uyuşmazsa `TOTAL_MISMATCH` (`needsManualReview = true`) işaretlenir.

### B. Test (`TEST` — `TestOmrProcessor.kt`, Siyah Kare Köşe Tespiti & `Optik Form 3 / 4` Düzeni)
- **Ön İşleme & Netlik:** Görüntü `TARGET_WORK_WIDTH = 1600.0` genişliğine ölçeklenir. Canlı kamera önizlemesinde (`isLiveCamera && !isSingleShotCapture`) Laplacian varyansı `MIN_LAPLACIAN_VARIANCE` (`18.0`) altındaysa `GuidanceState.Blurry` döner.
- **Siyah Kare Köşe Tespiti (`detectSquareCornerMarkers`):**
  1. `Core.normalize(NORM_MINMAX)` + `GaussianBlur(5x5)` sonrası birincil `adaptiveThreshold(..., 81, 10.0)` ve yedek `THRESH_OTSU` ile ikili görüntü oluşturulur; `RETR_LIST` konturları aranır.
  2. Her aday kontur 5 filtreden geçirilir: `4..6` köşeli dışbükey `approxPolyDP` (`0.05 * peri`), kare oranı `0.60..1.65`, doluluk `>= 0.65`, boyut `0.018..0.16 * imgW`, `innerMean < 145.0` ve dış 4 şeritten en parlak 2 şeridin ortalamasıyla `(brightTwoSidesMean - innerMean) >= 30.0`.
  3. 4 dış köşe (`TL, TR, BR, BL`) dikey dörtgen geometrisi (`0.26..0.75`, paralel fark `<= 0.22`, alan `>= 0.05`, yükseklik `>= 0.25`, genişlik `>= 0.14`) ile doğrulanır; başarısızsa bulunan 1–3 kısmi nokta ile birlikte `GuidanceState.MissingMarkers` döner.
- **Sabit Izgara Okuma (`OpticalFormLayout` + `readTestGrid`):**
  - `CLAHE(5.0, 8x8)` + `GaussianBlur(3x3)` + `NORM_MINMAX` + `erode(3x3)` uygulanmış ters görüntüde (`cleanInv`) `0.50 × 0.50` iç bölge örneklenir:
  - Üst blok `T.C KİMLİK NO` (`11 × 10`): `left = 99.0`, `right = 779.0`, `top = 334.0`, `bottom = 770.0`
  - Alt-sol blok `OKUL NO` (`5 × 10`): `left = 99.0`, `right = 408.0`, `top = 905.0`, `bottom = 1349.0`
  - Alt-sağ blok `CEVAPLAR` (`25 × 5`, `A..E`): `left = 523.0`, `right = 838.0`, `top = 859.0`, `bottom = 1971.0`
- **First-Large-Gap Dinamik Eşikleme:**
  - `best >= baseline + 0.09` ve `best - second >= 0.07` ise ilgili rakam/şık seçilir.
  - `second >= baseline + 0.11` ve `best - second < 0.05` ise `MULTI_MARK` (`"MULTI"` / `'?'`) işaretlenir; aksi halde boş (`""` / `'?'`) bırakılır.
- **Test Puanlama:** 1–25 arası soru şıkları (A–E) okunur, `answerKey` ve `maxScores` ile karşılaştırılarak doğru/yanlış/boş sayıları ve toplam puan hesaplanır.

### C. İnceleme Nedenleri (`ReviewReason`)
- `TOTAL_MISMATCH`, `TC_OKUL_NO_CONFLICT`, `CLASS_MISMATCH`, `MULTI_MARK`, `MAX_SCORE_EXCEEDED`, `MISSING_IDENTITY`, `AMBIGUOUS_MATCH`, `UNMATCHED_STUDENT`, `MULTIPLE_FORMS`, `DUPLICATE_ON_MIGRATION`.

---

## 4. Yerel Depolama ve Tercih Anahtarları

Bu projede `localStorage` yerine Android **Room (SQLite)** ve **SharedPreferences** kullanılır:

- **Room Veritabanı (`optiklik.db`):**
  - `exam_sessions`: Sınav oturumları
  - `exam_results`: Okunan öğrenci sonuçları (`examSessionId` foreign key + `(examSessionId, tcKimlikNo)` ve `(examSessionId, okulNo)` unique indeksleri)
  - `student_directory`: Öğrenci rehberi (`primaryKeys = ["tcKimlikNo", "okulAdi"]`)
- **SharedPreferences (`optiklik_teacher_profile`):**
  - `ad_soyad` (String): Öğretmen adı soyadı
  - `brans` (String): Branş
  - `varsayilan_yil` (String): Varsayılan eğitim-öğretim yılı (boşsa `"2026-2027"`)
  - `okullar` (String/JSON Array): Kayıtlı okul adları listesi
  - `debug_mode_enabled` (Boolean): Hata ayıklama görselleri modu
