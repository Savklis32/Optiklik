# Optiklik

**Optiklik**; öğretmenlerin **Yazılı Yoklama (`WRITTEN`)** ve **Çoktan Seçmeli Test (`TEST`)** optik formlarını canlı kamera, galeri veya çok sayfalı PDF dosyaları üzerinden **OpenCV** ile okuyup e-Okul öğrenci rehberiyle otomatik eşleştiren, soru bazlı başarı analizi üreten ve sonuçları **Excel (`.xlsx`)** ile **e-Okul OYSS** ekranına aktaran, **%100 çevrimdışı (offline)** çalışan bir Android uygulamasıdır.

---

## İçindekiler

- [Öne Çıkan Özellikler](#öne-çıkan-özellikler)
- [Desteklenen Sınav Türleri ve Optik Form Şablonları](#desteklenen-sınav-türleri-ve-optik-form-şablonları)
- [Adım Adım Kullanım Akışı](#adım-adım-kullanım-akışı)
- [OMR (Optik Okuma) Motoru ve Teknik Mimari](#omr-optik-okuma-motoru-ve-teknik-mimari)
- [Proje Klasör Yapısı](#proje-klasör-yapısı)
- [Veritabanı ve Yerel Depolama](#veritabanı-ve-yerel-depolama)
- [Teknik Altyapı ve Bağımlılıklar](#teknik-altyapı-ve-bağımlılıklar)
- [Kurulum, Derleme ve Test](#kurulum-derleme-ve-test)
- [Gizlilik, İzinler ve Sorumluluk Reddi](#gizlilik-izinler-ve-sorumluluk-reddi)
- [Lisans](#lisans)

---

## Öne Çıkan Özellikler

### 1. Çift Modlu Optik Okuma (Yazılı + Test)
- **Yazılı Yoklama (`WRITTEN`):** 1–10 soru arası iki basamaklı puan kodlaması, 11 haneli T.C. Kimlik No, 5 haneli Okul No ve 3 haneli kodlu toplam puan okuma. Hesaplanan toplam ile kodlanan toplam uyuşmazsa otomatik `TOTAL_MISMATCH` uyarısı üretir.
- **Çoktan Seçmeli Test (`TEST`):** 1–25 soru arası (A–E) şık okuma, özelleştirilebilir cevap anahtarı ve soru puan dağılımı (toplam 100 puan doğrulaması, "Puanları Eşit Dağıt" ve 10/15/20/25 soru kısayolları). Doğru, Yanlış, Boş sayıları ve toplam puan anlık hesaplanır.

### 2. Gelişmiş Canlı Kamera ve Toplu Tarama
- **Canlı Kamera (`CameraX`):**
  - Gerçek zamanlı köşe kilitleme ve ekranda yeşil `+` hedef göstergesi.
  - Test modunda `ImageCapture` ile yüksek çözünürlüklü tek çekim ve dikey kılavuz çerçevesi (`%82 × %84` ROI) kırpması.
  - Loş sınıf ortamları için **Flaş (Torch)** aç/kapa ve ekrana **Dokunarak Odaklama (Tap-to-Focus)**.
  - **Otomatik Tara** (3 kararlı kare doğrulaması + 5 saniye mükerrer koruma) ve **Manuel Tara** seçenekleri.
  - Başarılı okumada sesli bip ve titreşim geri bildirimi.
- **Dosyadan Toplu Tarama (JPG / PNG / Çok Sayfalı PDF):**
  - Çok sayfalı PDF dosyalarını 300 DPI (`2480 px` genişlik) kalitesinde otomatik işleme.
  - EXIF yön bilgisine göre otomatik fotoğraf döndürme.
  - Canlı ilerleme çubuğu, sayfa bazlı başarı/hata raporu, elle düzeltme ve hata ayıklama (Debug) görseli inceleme.

### 3. Akıllı Öğrenci Rehberi ve Eşleştirme
- e-Okul'dan indirilen **OOG01001R090 Fotoğraflı-Kimlik Bilgili Öğrenci Listesi** (`.xls`, `.xlsx`, `.pdf`, `.csv`) dosyalarını tek dokunuşla içe aktarma.
- **Çift Anahtarlı Eşleştirme:** Önce T.C. Kimlik No (algoritmik doğrulama ile), yoksa Okul No üzerinden otomatik eşleştirme.
- Mükerrer kayıt kontrolü, sınıf uyuşmazlığı tespiti ve **Okunmayan Öğrenciler** (sınava girmeyen / kağıdı atlanan) listesi.

### 4. Profesyonel Raporlama ve e-Okul OYSS Aktarımı
- **2 Sayfalı Excel Raporu (`.xlsx`):**
  1. **Sınav Sonuçları:** Kurumsal üst bilgi (Okul, Eğitim-Öğretim Yılı, Dönem, Ders, Sınıf/Şube, Öğretmen), öğrenci bazlı soru puanları, `=AVERAGE(...)` formüllü sınıf ortalaması, isteğe bağlı **T.C. Maskeleme**; Test sınavlarında ek olarak **Doğru / Yanlış / Boş** ve **Öğrenci Cevapları** sütunları.
  2. **Soru Analizi:** Her soru için maksimum puan, soru ortalaması, başarı yüzdesi (`%`), en yüksek/en düşük puan, sıfır alan öğrenci sayısı ve katılan öğrenci sayısı.
- **e-Okul OYSS Chrome Eklentisi:** Uygulama içinden dışa aktarılabilen `E-OKUL_OYSS_NOT_AKTARICI.rar` eklentisi ile Excel notlarını e-Okul OYSS ekranına (`IOK10020` / `OOK10020`) Okul No eşleştirmesiyle hızlı aktarma.
- **Uygulama İçi 8 Adımlı Kullanım Kılavuzu:** Çevrimdışı çalışan etkileşimli yerel HTML kılavuz (`assets/kullanimkilavuzu.html`).

---

## Desteklenen Sınav Türleri ve Optik Form Şablonları

Uygulama içerisindeki **Formlar** menüsünden aşağıdaki 4 şablon doğrudan **İndirilenler** klasörüne kaydedilebilir veya **Paylaş** ile bilgisayara aktarılabilir:

| Şablon Dosyası | Sınav Türü | Düzen & Köşe İşaretçisi | Kullanım Amacı |
| :--- | :--- | :--- | :--- |
| `Optik Form1.jpg` | Yazılı (`WRITTEN`) | Yatay 13 blok, 4 iç içe halka (bullseye) | Sınav kağıdının üstüne eklemek veya tekli baskı almak için |
| `Optik Form2.pdf` | Yazılı (`WRITTEN`) | Tek A4'te 2 adet yazılı formu | Çıktı alıp ortadan keserek tek form halinde dağıtmak için |
| `Optik Form3.jpg` | Test (`TEST`) | Dikey 25 soru (A–E), 4 içi dolu siyah kare | Test kağıdına eklemek veya tekli baskı almak için |
| `Optik Form4.pdf` | Test (`TEST`) | Yatay A4'te 2 adet test formu | Çıktı alıp ortadan keserek her öğrenciye tek form vermek için |

> **Önemli Kural:** 2'li A4 şablonlarını (`Optik Form2.pdf` ve `Optik Form4.pdf`) yazdırdıktan sonra **mutlaka ortadan keserek** her öğrenciye bir adet verin ve tararken her fotoğrafta / PDF sayfasında **yalnızca tek bir optik form** bulunmasına dikkat edin.

---

## Adım Adım Kullanım Akışı

1. **Öğretmen Profili:** Ana ekrandan ad soyad, branş, eğitim-öğretim yılı ve görev yaptığınız okulları kaydedin.
2. **Öğrenci Rehberi:** e-Okul'dan `OOG01001R090` sınıf listesini indirip **Rehber > Liste Yükle** üzerinden ekleyin.
3. **Optik Form Şablonu:** **Formlar** menüsünden sınav türüne uygun şablonu indirip yazdırın.
4. **Sınav Oturumu Oluşturma:**
   - Yazılı için **Yeni Sınav (Yazılı)** ile soru sayısı (1–10) ve 100 puan dağılımını girin.
   - Test için **Yeni Sınav (Test)** ile soru sayısı (1–25), A–E cevap anahtarı ve soru puanlarını belirleyin.
5. **Optik Okuma:** Sınav kartından **Kamera** veya **Dosya/PDF** seçeneğiyle kağıtları okutun.
6. **Sonuçlar ve Excel:** **Sonuçlar** ekranından sınıf ortalamasını, soru analizini, kontrol gerektiren kayıtları ve okunmayan öğrencileri inceleyip 2 sayfalı `.xlsx` raporunu dışa aktarın.
7. **e-Okul OYSS Aktarımı:** Ana ekrandaki **E-OKUL OYSS NOT AKTARICI** kartından Chrome eklentisini indirip bilgisayarınıza kurun, Excel dosyasını seçerek notları e-Okul'a aktarın.

---

## OMR (Optik Okuma) Motoru ve Teknik Mimari

### Yazılı Motoru (`OmrProcessor.kt`)
- **Köşe Tespiti:** `1600 px` ölçekli gri görüntüde `RETR_TREE` iç içe kontur hiyerarşisi (`depth >= 2`) + 5 bantlı radyal kontrast doğrulaması; yedek olarak çok ölçekli CLAHE şablon eşleştirme (`TM_CCOEFF_NORMED`) ve 3 karelik canlı kamera histerezisi.
- **Perspektif ve Izgara:** 4 köşe merkezinden `2100 × 720` kanonik boyuta `warpPerspective`, 13 bloğun (T.C., Okul No, S1–S10, Toplam) dinamik sınır tespiti, morfolojik erozyon ile ince basılı rakamların bastırılması ve adaptif dinamik eşikleme.

### Test Motoru (`TestOmrProcessor.kt`)
- **Köşe Tespiti (ZipGrade / ÖSYM Mantığı):** `Core.normalize(NORM_MINMAX)` + `GaussianBlur(5×5)` sonrası `adaptiveThreshold(blockSize=81, C=10)` ve `Otsu` yedeği ile `RETR_LIST` kontur taraması. Her aday 5 filtreden geçirilir: dışbükey `4..6` köşeli çokgen (`approxPolyDP`), kare en-boy oranı (`0.60..1.65`), doluluk (`>= 0.65`), boyut filtresi ve karenin 4 dış kenarından en parlak 2 kenara göre quiet-zone kontrast doğrulaması.
- **Perspektif ve Izgara:** 4 siyah kare merkezinden `1000 × 2000` kanonik boyuta `warpPerspective`, kalibre edilmiş `OpticalFormLayout` koordinatları (T.C. `11×10`, Okul No `5×10`, Cevaplar `25×5`) ve **First-Large-Gap** dinamik eşikleme ile şık/çoklu işaretleme (`MULTI` / `Ç`) tespiti.

### İnceleme Bayrakları (`ReviewReason`)
`TOTAL_MISMATCH`, `TC_OKUL_NO_CONFLICT`, `CLASS_MISMATCH`, `MULTI_MARK`, `MAX_SCORE_EXCEEDED`, `MISSING_IDENTITY`, `AMBIGUOUS_MATCH`, `UNMATCHED_STUDENT`, `MULTIPLE_FORMS`, `DUPLICATE_ON_MIGRATION`.

---

## Proje Klasör Yapısı

```text
Optiklik/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
└── app/
    ├── build.gradle.kts
    ├── schemas/                           # Room veritabanı şema geçmişi
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   ├── kullanimkilavuzu.html  # 8 adımlı çevrimdışı kullanım kılavuzu
        │   │   ├── eklenti/               # E-OKUL_OYSS_NOT_AKTARICI.rar
        │   │   └── formlar/               # Optik Form1..4 (jpg / pdf)
        │   └── java/com/optiklik/app/
        │       ├── MainActivity.kt
        │       ├── OptiklikApp.kt
        │       ├── data/
        │       │   ├── local/             # OptiklikDatabase, DAO ve Entity sınıfları
        │       │   ├── preferences/       # TeacherProfilePreferences
        │       │   └── repository/        # OptiklikRepository
        │       ├── domain/omr/            # OmrProcessor, TestOmrProcessor, TcKimlikValidator
        │       ├── service/               # BatchScannerService, ExcelExporter, FormTemplateExporter, StudentListParser
        │       └── ui/                    # Home, LiveScanner, BatchScan, ExamResults, StudentDirectory, Navigation
        └── test/                          # Birim testleri (Migration, OMR Fixture, EXIF, Locale, Parser)
```

---

## Veritabanı ve Yerel Depolama

Uygulama tüm verileri cihaz içinde **Room (SQLite — `optiklik.db`)** ve **SharedPreferences** üzerinde tutar:

- **`exam_sessions`:** Sınav oturumları (`WRITTEN` / `TEST`, soru sayısı, soru puanları, cevap anahtarı).
- **`exam_results`:** Öğrenci sınav sonuçları (`examSessionId` foreign key, `(examSessionId, tcKimlikNo)` ve `(examSessionId, okulNo)` unique indeksleri, `scoresJson`, `studentAnswersJson`, `reviewReasons`).
- **`student_directory`:** `(tcKimlikNo, okulAdi)` birincil anahtarlı öğrenci rehberi.
- **`optiklik_teacher_profile` (`SharedPreferences`):** Öğretmen adı soyadı, branşı, varsayılan eğitim-öğretim yılı, kayıtlı okullar ve hata ayıklama (debug) modu tercihi.

---

## Teknik Altyapı ve Bağımlılıklar

| Bileşen | Teknoloji / Sürüm |
| :--- | :--- |
| **Dil & Hedef SDK** | Kotlin `2.0.21` • Java `17` • `minSdk 26` (Android 8.0) • `targetSdk / compileSdk 35` |
| **Arayüz (UI)** | Jetpack Compose (`BOM 2024.11.00`) • Material 3 • Navigation Compose `2.8.4` |
| **Yerel Veritabanı** | Room `2.6.1` (KSP) |
| **Kamera** | CameraX `1.4.0` (`core`, `camera2`, `lifecycle`, `view`) |
| **Görüntü İşleme (OMR)** | OpenCV Android `4.10.0` (`arm64-v8a`, `armeabi-v7a`) |
| **Excel İşleme** | Apache POI OOXML `5.2.5` (`.xls` / `.xlsx`) |
| **PDF İşleme** | PDFBox Android `2.0.27.0` & Android `PdfRenderer` |
| **Diğer** | Kotlin Coroutines `1.9.0` • AndroidX ExifInterface `1.3.7` |
| **Test** | JUnit 4 • Robolectric `4.13` • Room Testing • Coroutines Test |

---

## Kurulum, Derleme ve Test

### Android Studio ile
1. Bu depoyu klonlayın veya ZIP olarak indirin:
   ```bash
   git clone https://github.com/Savklis32/Optiklik.git
   ```
2. Android Studio'da **File > Open** menüsünden proje kök dizinini açın.
3. Gradle senkronizasyonu tamamlandıktan sonra fiziksel cihazda veya emülatörde çalıştırın.

### Komut Satırı (Gradle) ile
```bash
# Tüm birim testlerini çalıştırmak için:
./gradlew testDebugUnitTest

# Türkçe locale birim testlerini çalıştırmak için:
./gradlew testTrLocale

# Debug APK oluşturmak için:
./gradlew assembleDebug
```
*(Windows PowerShell veya CMD üzerinde `./gradlew` yerine `.\gradlew.bat` kullanabilirsiniz.)*

---

## Gizlilik, İzinler ve Sorumluluk Reddi

- **%100 Çevrimdışı ve Yerel:** Optiklik hiçbir kişisel veriyi, öğrenci bilgisini, sınav sonucunu veya taranan görüntüyü toplamaz, işlemez ve harici bir sunucuya aktarmaz.
- **Asgari İzinler:** Uygulama yalnızca optik formları taramak için `CAMERA` iznini ve başarılı okuma bildirimi için `VIBRATE` özelliğini kullanır.
- **Sorumluluk Reddi:** Optiklik, ölçme ve değerlendirme süreçlerini hızlandırmak amacıyla geliştirilmiş yardımcı bir araçtır. Optik okuma sonuçlarının doğruluğunu kontrol etmek, olası işaretleme hatalarını düzeltmek ve notları resmî sistemlere (e-Okul vb.) aktarmak kullanıcının sorumluluğundadır.

---

## Lisans

Bu proje [MIT Lisansı](LICENSE) altında lisanslanmıştır.
