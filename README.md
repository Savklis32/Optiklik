# Optiklik

**Optiklik**; öğretmenlerin 13 bloklu optik formları (T.C. Kimlik No, Okul No, 10 soru puanı ve Toplam) canlı kamera, galeri veya çok sayfalı PDF üzerinden OpenCV ile okuyup öğrenci rehberiyle eşleştiren, soru analizi yapan ve sonuçları Excel (`.xlsx`) ile e-Okul OYSS ekranına aktaran tamamen çevrimdışı (offline) bir Android uygulamasıdır.

---

## Öne Çıkan Özellikler

- **Canlı Kamera Taraması:** Otomatik algılama ve tek çekim (manuel deklanşör) desteğiyle hızlı optik form okuma.
- **Toplu PDF ve JPG Tarama:** Cihaz galerisinden çoklu görsel seçimi veya çok sayfalı tarayıcı PDF dosyalarını tek seferde toplu okuma.
- **e-Okul Öğrenci Rehberi İçe Aktarma:** `.xls`, `.xlsx`, `.pdf` ve `.csv` formatlarındaki e-Okul sınıf listelerini doğrudan uygulama içi öğrenci rehberine aktarma ve okunan formları otomatik eşleştirme.
- **Uygulama İçi Optik Form Şablonları:** Tekli baskı ve sınav kağıdı üstü kullanım için `Optik Form1.jpg` ve tek A4 sayfasında 2'li baskı için `Optik Form2.pdf` şablonlarını doğrudan indirme ve paylaşma.
- **2 Sayfalı Excel Raporu (`.xlsx`):**
  1. **Sınav Sonuçları:** Öğrenci bilgileri, soru bazlı puanlar ve toplam puanlar.
  2. **Soru Analizi:** Soru bazlı başarı yüzdeleri, ortalamalar ve değerlendirme istatistikleri.
- **e-Okul OYSS Chrome Eklentisi Entegrasyonu:** Uygulama içerisinden dışa aktarılabilen Chrome eklentisi ile Excel'deki notları e-Okul OYSS puan giriş ekranına (IOK10020 / OOK10020) hızlı ve güvenli aktarma.
- **Uygulama İçi Kullanım Kılavuzu:** Çevrimdışı çalışan yerel HTML kullanım kılavuzu (`kullanim_kilavuzu.html`) ile adım adım rehberlik.

---

## Adım Adım Kullanım Özeti

1. **Öğretmen Profili:** Ad Soyad, branş, eğitim-öğretim yılı ve görev yaptığınız okul bilgilerini tanımlayın.
2. **e-Okul Öğrenci Listesi Yükleme:** e-Okul'dan aldığınız sınıf listesini (`.xls`, `.xlsx`, `.pdf`, `.csv`) Öğrenci Rehberi ekranından içe aktarın.
3. **Optik Form Hazırlama:** Ana ekrandaki **Formlar** menüsünden `Optik Form1.jpg` veya `Optik Form2.pdf` şablonunu indirip sınav kağıtlarınıza uygulayın.
4. **Sınav Oturumu Oluşturma:** **Yeni Sınav (Yazılı)** butonuna dokunarak okul, sınıf, ders, soru sayısı ve soru puanlarını belirleyin.
5. **Kamera / PDF Okuma:** Oluşturduğunuz sınav oturumunda Canlı Kamera veya Toplu PDF/JPG Tarama seçeneğiyle öğrenci kağıtlarını okutun.
6. **Excel Çıktısı:** Okuma tamamlandığında sonuçları inceleyin, gerekirse düzenleyin ve 2 sayfalı Excel (`.xlsx`) raporunu dışa aktarın.
7. **e-Okul OYSS Aktarımı:** Ana ekrandaki **E-OKUL OYSS NOT AKTARICI** kartından Chrome eklentisini indirip kurun, ardından Excel dosyanızı seçerek notları e-Okul OYSS ekranına aktarın.

---

## Teknik Altyapı

| Bileşen | Teknoloji / Kütüphane |
| :--- | :--- |
| **Dil** | Kotlin |
| **Arayüz (UI)** | Jetpack Compose & Material 3 |
| **Yerel Veritabanı** | Room (v9) |
| **Kamera** | CameraX |
| **Görüntü İşleme / OMR** | OpenCV 4.10 |
| **Excel İşleme** | Apache POI (`.xls` / `.xlsx`) |
| **PDF İşleme** | PDFBox Android |

---

## Kurulum ve Derleme

### Android Studio ile
1. Bu depoyu klonlayın veya indirin.
2. Android Studio'da **File > Open** üzerinden proje kök klasörünü açın.
3. Gradle senkronizasyonunun tamamlanmasını bekleyin ve projeyi çalıştırın / derleyin.

### Komut Satırı (Gradle) ile
```bash
# Birim testlerini çalıştırmak için:
./gradlew testDebugUnitTest

# Debug APK oluşturmak için:
./gradlew assembleDebug
```
*(Windows PowerShell veya CMD üzerinde `./gradlew` yerine `.\gradlew.bat` kullanabilirsiniz.)*

---

## Gizlilik ve Sorumluluk Reddi

- **%100 Çevrimdışı Çalışma:** Optiklik hiçbir kişisel veriyi, öğrenci bilgisini, sınav sonucunu veya taranan görüntüyü toplamaz, işlemez ve harici bir sunucuya göndermez. Tüm veriler yalnızca kullanıcının kendi cihazında yerel olarak saklanır.
- **İzinler:** Uygulama yalnızca optik formları taramak için **Kamera** iznini ve başarılı okuma bildirimi için **Titreşim** özelliğini kullanır.
- **Sorumluluk Reddi:** Optiklik, ölçme ve değerlendirme süreçlerini hızlandırmak amacıyla geliştirilmiş yardımcı bir araçtır. Optik okuma sonuçlarının doğruluğunu kontrol etmek ve notları resmî sistemlere (e-Okul vb.) aktarmak kullanıcının sorumluluğundadır.

---

## Lisans

Bu proje [MIT Lisansı](LICENSE) altında lisanslanmıştır.
