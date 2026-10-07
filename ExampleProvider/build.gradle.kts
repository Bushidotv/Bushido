dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Sürüm numarasını tam sayı (Integer) olarak belirtiyoruz
version = 32

cloudstream {
    // Eklentimizin uygulamanin uzantilar sayfasinda gorunecek bilgileri
    description = "Canli TV yayinlari & Guncel MMA Mac tekrarlari"
    authors = listOf("BushidoTV")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Uzantının durumunu aktif (Ok) olarak işaretliyoruz

    tvTypes = listOf("Live", "Movie")

    requiresResources = true
    language = "tr"

    iconUrl = "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
