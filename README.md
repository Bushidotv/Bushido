<div align="center">

# 🥊 Bushido CloudStream Eklentisi

**CloudStream 3 için Canlı TV, Dövüş Sporları (MMA/UFC) ve Film & Dizi Sağlayıcısı**

[![CloudStream](https://img.shields.io/badge/CloudStream-v3-blue.svg)](https://github.com/recloudstream/cloudstream)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Android%20TV-green.svg)]()
[![Language](https://img.shields.io/badge/Dil-T%C3%BCrk%C3%A7e-red.svg)]()
[![Status](https://img.shields.io/badge/Durum-%C3%87al%C4%B1%C5%9F%C4%B1yor-brightgreen.svg)]()

---

</div>

## 📌 Eklentiler

Bu depo, her biri **ayrı kurulan** CloudStream eklentilerinden oluşur:

| Eklenti | İçerik |
|---|---|
| **Bushido Spor** | 📺 Canlı spor ve TV yayınları |
| **HDFilmCehennemi** | 🎬 Film ve dizi arşivi |
| **TRanimeci** | ⛩️ Türkçe anime arşivi ve güncel bölümler |
| **DiziPalHub** | 📺 Diziler ve filmler (hazır `.cs3` olarak yayınlanır) |
| **DiziMom** | 📺 Diziler (hazır `.cs3` olarak yayınlanır) |
| **BelgeselX** | 🌍 Belgeseller (hazır `.cs3` olarak yayınlanır) |
| **RecTV** | 🎬 Filmler, diziler ve canlı yayınlar (hazır `.cs3` olarak yayınlanır) |
| **FullHDFilmizlesene** | 🎬 Filmler ve diziler (hazır `.cs3` olarak yayınlanır) |

---

## 📲 Kurulum Rehberi

Eklentiyi CloudStream uygulamanıza eklemek son derece basittir:

1. **CloudStream 3** uygulamasını açın.
2. Sağ alttaki **Ayarlar (Settings)** sekmesine gidin.
3. **Uzantılar (Extensions)** veya **Depolar (Repositories)** seçeneğine tıklayın.
4. **"Depo Ekle" (Add Repository)** butonuna basın.
5. Açılan pencerede bilgileri girin:
   * **Depo Adı:** `Bushido`
   * **Depo URL'si:**
     ```text
     https://raw.githubusercontent.com/Bushidotv/Bushido/master/repo.json
     ```
6. **İndir / Ekle** butonuna tıklayın.
7. Depo eklendikten sonra listeden istediğiniz eklentileri bulup **Yükle (Install)** butonuna basarak kurulumu tamamlayın.

---

## ⚙️ Desteklenen Platformlar

* 📱 **Android Telefonlar ve Tabletler**
* 📺 **Android TV & Google TV**
* 🖥️ **Fire TV Stick**

---

## 🛠️ Depo Düzeni (Geliştirici Notları)

```text
Bushido/  HDFilmCehennemi/  ...     → kaynak koddan derlenen eklentiler (her klasör ayrı .cs3)
prebuilt/                               → hazır .cs3 dosyaları (olduğu gibi yayınlanır)
scripts/merge_plugins.py                → plugins.json'u hazır dosyalarla birleştirir
```

Her `master` güncellemesinde GitHub Actions tüm eklentileri derler, `prebuilt/` içindeki hazır dosyaları ekler ve `builds` dalını günceller. Eklenti listesi bu daldaki `plugins.json`'dan okunur.

**Hazır `.cs3` eklemek:** Dosyayı `prebuilt/` klasörüne koyup `master`'a gönderin, başka bir şey gerekmez. İsterseniz aynı adlı bir `.json` dosyası (ör. `prebuilt/BelgeselX.json`) ile listedeki `description`, `authors`, `tvTypes`, `language`, `iconUrl`, `status` alanlarını ayarlayabilirsiniz. Dosyayı kaldırınca eklenti de listeden kalkar.

**Kaynak koddan yeni eklenti eklemek:** Kök dizinde yeni bir klasör açıp içine `build.gradle.kts` ve `src/main/kotlin/...` koyun (örnek: `HDFilmCehennemi/`). Klasör adı eklentinin adı olur ve otomatik derlenir.

---

## ⚠️ Yasal Uyarı (Disclaimer)

Bu yazılım yalnızca eğitim ve araştırma amacıyla geliştirilmiştir. 

* Bu eklenti sunucularında hiçbir video, medya veya yayın barındırmaz.
* Yalnızca internette kamuya açık olarak yayın yapan üçüncü taraf sitelerdeki içerikleri CloudStream arayüzünde düzenli bir şekilde görüntülemeye yarayan bir kazıyıcıdır (scraper).
* Yayınlanan içeriklerin telif hakları ilgili hak sahiplerine aittir.

---

<div align="center">
<sub>BushidoTV topluluğu için hazırlanmıştır.</sub>
</div>
