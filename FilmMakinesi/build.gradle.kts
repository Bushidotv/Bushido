// Sürüm numarasını tam sayı (Integer) olarak belirtiyoruz
version = 1

cloudstream {
    description = "FilmMakinesi filmleri ve dizileri"
    authors = listOf("BushidoTV")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    language = "tr"

    iconUrl = "https://raw.githubusercontent.com/Bushidotv/Bushido/master/icon.png"
}
