package com.mpedreira.nimwifi

/**
 * Public Internet speed-test destinations offered in *Pruebas de velocidad* (RC4 · 1). Each has a
 * stable [id] that is saved with the settings, so the list can change without losing a choice.
 *
 * Internet público measures download and upload up to an external provider; the private iPerf3
 * target stays the measurement against the site's wired server. Load is moderate: few parallel
 * connections and bounded chunks, for control points chosen during the survey and *Probar
 * servidor*, not for continuous use.
 *
 * LibreSpeed entries come from the official list (github.com/librespeed/speedtest, server-list.json)
 * with the paths each one publishes; they were checked to answer download (`garbage.php`) and
 * upload (`empty.php`) over HTTPS. LibreSpeed publishes no usage conditions for third-party
 * clients: these are public servers lent by their sponsors, used sparingly.
 */
data class InternetServer(
    val id: String,
    /** Shown in the drop-down: `LibreSpeed · Ámsterdam · Clouvider`. */
    val label: String,
    val provider: String,
    val city: String?,
    val operator: String?,
    val downloadUrl: String,
    val uploadUrl: String,
    /** Parallel connections for this provider. */
    val streams: Int,
    /** Largest upload request body for this provider. */
    val maxUploadChunkBytes: Int
)

object InternetServers {
    const val CUSTOM = "custom"
    const val CLOUDFLARE = "cloudflare-auto"

    /** Cloudflare's anycast network picks the data centre; the one observed is reported (CF-RAY). */
    val cloudflare = InternetServer(
        CLOUDFLARE, "Cloudflare · ubicación automática", "Cloudflare", null, null,
        ThroughputSettings.DEFAULT_DOWNLOAD_URL, ThroughputSettings.DEFAULT_UPLOAD_URL,
        streams = 4, maxUploadChunkBytes = 16 * 1024 * 1024
    )

    /** 10 MiB per download request (LibreSpeed accepts 4..1024 MiB) and 4 MB upload bodies. */
    private fun libreSpeed(id: String, city: String, operator: String, base: String) = InternetServer(
        id, "LibreSpeed · $city · $operator", "LibreSpeed", city, operator,
        "${base}garbage.php?ckSize=10", "${base}empty.php",
        streams = 2, maxUploadChunkBytes = 4 * 1024 * 1024
    )

    val all: List<InternetServer> = listOf(
        cloudflare,
        libreSpeed("librespeed-ams-clouvider", "Ámsterdam", "Clouvider", "https://ams.speedtest.clouvider.net/backend/"),
        libreSpeed("librespeed-fra-clouvider", "Fráncfort", "Clouvider", "https://fra.speedtest.clouvider.net/backend/"),
        libreSpeed("librespeed-lon-clouvider", "Londres", "Clouvider", "https://lon.speedtest.clouvider.net/backend/"),
        libreSpeed("librespeed-rom-garr", "Roma", "GARR", "https://st-be-rm2.infra.garr.it/")
    )

    fun byId(id: String?): InternetServer? = all.firstOrNull { it.id == id }

    /** The catalogue entry whose URLs are exactly these, else [CUSTOM]. */
    fun match(downloadUrl: String, uploadUrl: String): String =
        all.firstOrNull { it.downloadUrl == downloadUrl.trim() && it.uploadUrl == uploadUrl.trim() }?.id ?: CUSTOM

    /** Default streams and upload chunk for a custom destination (the client's defaults). */
    const val CUSTOM_STREAMS = 4
    const val CUSTOM_MAX_UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024
}
