package com.wiggletonabbey.wigglebot.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "ToolDispatcher"

// ── Serialization models for Google Directions API ──────────────────────────

@Serializable
private data class DirectionsResponse(
    val status: String,
    val routes: List<DirectionsRoute> = emptyList(),
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
private data class DirectionsRoute(
    val legs: List<DirectionsLeg> = emptyList(),
)

@Serializable
private data class DirectionsLeg(
    @SerialName("departure_time") val departureTime: DirectionsTime? = null,
    @SerialName("arrival_time") val arrivalTime: DirectionsTime? = null,
    val duration: DirectionsText,
    val steps: List<DirectionsStep> = emptyList(),
)

@Serializable
private data class DirectionsTime(val text: String)

@Serializable
private data class DirectionsText(val text: String)

@Serializable
private data class DirectionsStep(
    @SerialName("travel_mode") val travelMode: String,
    val duration: DirectionsText,
    @SerialName("transit_details") val transitDetails: TransitDetails? = null,
)

@Serializable
private data class TransitDetails(
    @SerialName("departure_stop") val departureStop: TransitStop,
    @SerialName("arrival_stop") val arrivalStop: TransitStop,
    @SerialName("departure_time") val departureTime: DirectionsTime,
    @SerialName("arrival_time") val arrivalTime: DirectionsTime? = null,
    val line: TransitLine,
    @SerialName("num_stops") val numStops: Int = 0,
)

@Serializable
private data class TransitStop(val name: String)

@Serializable
private data class TransitLine(
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val vehicle: TransitVehicle,
)

@Serializable
private data class TransitVehicle(val name: String, val type: String)

// ── Serialization models for external APIs ───────────────────────────────────

@Serializable
private data class OpenMeteoResponse(val current: OpenMeteoCurrent)

@Serializable
private data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("apparent_temperature") val feelsLike: Double,
    @SerialName("weathercode") val weatherCode: Int,
    @SerialName("windspeed_10m") val windSpeed: Double,
    val precipitation: Double,
)

@Serializable
private data class OverpassResponse(val elements: List<OverpassElement> = emptyList())

@Serializable
private data class OverpassElement(
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String> = emptyMap(),
)

// ─────────────────────────────────────────────────────────────────────────────

class ToolDispatcher(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    var googleMapsApiKey: String = ""

    // Separate lightweight client for external API calls (weather, Overpass).
    // Short timeouts — these should be fast.
    private val externalHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun dispatch(name: String, args: JsonObject?): String = withContext(Dispatchers.Main) {
        Log.d(TAG, "Dispatching tool: $name  args: $args")

        try {
            when (name) {

                "media_play_pause" -> {
                    val controls = MediaControllerHolder.transport()
                    if (controls != null) {
                        val isPlaying = MediaControllerHolder.get()?.playbackState?.state ==
                            android.media.session.PlaybackState.STATE_PLAYING
                        if (isPlaying) controls.pause() else controls.play()
                    } else {
                        sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                    "Toggled media play/pause."
                }

                "media_next_track" -> {
                    MediaControllerHolder.transport()?.skipToNext()
                        ?: sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                    "Skipped to next track."
                }

                "media_previous_track" -> {
                    MediaControllerHolder.transport()?.skipToPrevious()
                        ?: sendMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    "Went to previous track."
                }

                "spotify_search_play" -> {
                    val query = args?.get("query")?.jsonPrimitive?.content
                        ?: return@withContext "Missing query argument."
                    val type = args["type"]?.jsonPrimitive?.content ?: "track"
                    openSpotifySearch(query, type)
                }

                "audible_open" -> {
                    val title = args?.get("title")?.jsonPrimitive?.content
                    openAudible(title)
                }

                "launch_app" -> {
                    val appName = args?.get("app_name")?.jsonPrimitive?.content
                        ?: return@withContext "Missing app_name argument."
                    launchApp(appName)
                }

                "open_url" -> {
                    val url = args?.get("url")?.jsonPrimitive?.content
                        ?: return@withContext "Missing url argument."
                    openUrl(url)
                }

                "set_volume" -> {
                    val level = args?.get("level")?.jsonPrimitive?.content
                        ?: return@withContext "Missing level argument."
                    setVolume(level)
                }

                "send_notification" -> {
                    val title = args?.get("title")?.jsonPrimitive?.content ?: "WiggleBot"
                    val body = args?.get("body")?.jsonPrimitive?.content ?: ""
                    sendNotification(title, body)
                }

                "get_installed_apps" -> getInstalledApps()

                "get_location" -> getLocation()

                "get_weather" -> {
                    val location = args?.get("location")?.jsonPrimitive?.content
                    withContext(Dispatchers.IO) { fetchWeather(location) }
                }

                "find_nearby" -> {
                    val type = args?.get("type")?.jsonPrimitive?.content
                        ?: return@withContext "Missing type argument."
                    val location = args["location"]?.jsonPrimitive?.content
                    val radius = args["radius_meters"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5000
                    withContext(Dispatchers.IO) { fetchNearby(type, radius, location) }
                }

                "get_transit" -> {
                    val destination = args?.get("destination")?.jsonPrimitive?.content
                        ?: return@withContext "Missing destination argument."
                    val origin = args["origin"]?.jsonPrimitive?.content
                    withContext(Dispatchers.IO) { fetchTransit(destination, origin) }
                }

                "navigate_to" -> {
                    val destination = args?.get("destination")?.jsonPrimitive?.content
                        ?: return@withContext "Missing destination argument."
                    navigateTo(destination)
                }

                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: $name", e)
            "Error executing $name: ${e.message}"
        }
    }

    // ── Media ────────────────────────────────────────────────────────────────

    private fun sendMediaKeyEvent(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        )
        audioManager.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        )
    }

    private fun openSpotifySearch(query: String, type: String): String {
        // Spotify URI scheme: spotify:search:<query>
        // For direct search we use the deep link format
        val encodedQuery = Uri.encode(query)
        val spotifyUri = "spotify:search:$encodedQuery"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (isAppInstalled("com.spotify.music")) {
            intent.setPackage("com.spotify.music")
            context.startActivity(intent)
            "Opened Spotify searching for \"$query\"."
        } else {
            // Fallback: open Spotify web
            openUrl("https://open.spotify.com/search/$encodedQuery")
            "Spotify not installed; opened web search for \"$query\"."
        }
    }

    private fun openAudible(title: String?): String {
        val pkg = "com.audible.application"
        return if (isAppInstalled(pkg)) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (intent != null) {
                context.startActivity(intent)
                if (title != null) "Opened Audible. Search for \"$title\" manually — Audible doesn't expose a deep-link search URI."
                else "Opened Audible."
            } else "Could not get launch intent for Audible."
        } else "Audible is not installed."
    }

    // ── App launching ────────────────────────────────────────────────────────

    // Common app name → package ID mapping.
    // The model will use get_installed_apps if it's unsure.
    private val knownApps = mapOf(
        "spotify" to "com.spotify.music",
        "audible" to "com.audible.application",
        "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "signal" to "org.thoughtcrime.securesms",
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "pocket casts" to "au.com.shiftyjelly.pocketcasts",
        "overcast" to "fm.overcast.app",
        "netflix" to "com.netflix.mediaclient",
        "gmail" to "com.google.android.gm",
        "calendar" to "com.google.android.calendar",
        "camera" to "com.android.camera2",
        "settings" to "com.android.settings",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "discord" to "com.discord",
        "reddit" to "com.reddit.frontpage",
        "obsidian" to "md.obsidian",
        "anki" to "com.ichi2.anki",
    )

    private fun launchApp(appName: String): String {
        val pkg = knownApps[appName.lowercase()]
            ?: findPackageByName(appName)
            ?: return "Could not find an app named \"$appName\". Try get_installed_apps to see what's available."

        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return "Found package $pkg but couldn't get a launch intent."

        context.startActivity(intent)
        return "Launched $appName ($pkg)."
    }

    private fun findPackageByName(name: String): String? {
        val pm = context.packageManager
        val lowerName = name.lowercase()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains(lowerName) || app.packageName.contains(lowerName)
            }
            ?.packageName
    }

    private fun openUrl(url: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened URL: $url"
    }

    // ── System ───────────────────────────────────────────────────────────────

    private fun setVolume(level: String): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val target = when {
            level == "mute" -> 0
            level == "low" -> max / 4
            level == "medium" -> max / 2
            level == "high" -> max
            level.endsWith("%") -> {
                val pct = level.removeSuffix("%").trim().toIntOrNull() ?: 50
                (max * pct / 100)
            }
            else -> max / 2
        }

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), 0)
        return "Set media volume to $level ($target/$max)."
    }

    private fun sendNotification(title: String, body: String): String {
        val channelId = "wigglebot_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "WiggleBot", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        return "Notification posted: \"$title\" — $body"
    }

    private fun getInstalledApps(): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { "${pm.getApplicationLabel(it)} (${it.packageName})" }
            .sorted()
        return "Installed launchable apps:\n${apps.joinToString("\n")}"
    }

    // ── Driving / Location ───────────────────────────────────────────────────

    private fun getLocation(): String {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            return "Lat: ${loc.latitude}, Lon: ${loc.longitude}"
        }
        return "Location unavailable — ensure location permission is granted and GPS is on."
    }

    /** Returns (lat, lon) from GPS, or null. */
    private fun currentLatLon(): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            return loc.latitude to loc.longitude
        }
        return null
    }

    /**
     * Resolves a place name to (lat, lon) via Nominatim (OpenStreetMap).
     * Falls back to GPS if locationName is null.
     * Returns null with an error string on failure.
     */
    private fun resolveLatLon(locationName: String?): Pair<Pair<Double, Double>?, String?> {
        if (locationName == null) {
            val gps = currentLatLon()
            return if (gps != null) gps to null
            else null to "Location unavailable — GPS is off and no location was specified."
        }
        val encoded = Uri.encode(locationName)
        val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1"
        val responseText = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "WiggleBot/1.0")
                .get()
                .build()
            externalHttp.newCall(request).execute().use { it.body?.string() ?: "[]" }
        }.getOrElse { return null to "Geocoding failed: ${it.message}" }

        // Nominatim returns a JSON array; regex out the first lat/lon pair
        val latLon = runCatching {
            val lat = Regex("\"lat\":\"([^\"]+)\"").find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = Regex("\"lon\":\"([^\"]+)\"").find(responseText)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat != null && lon != null) lat to lon else null
        }.getOrNull()

        return if (latLon != null) latLon to null
        else null to "Could not find location \"$locationName\"."
    }

    private fun fetchWeather(locationName: String?): String {
        val (latLon, error) = resolveLatLon(locationName)
        if (latLon == null) return error ?: "Location unavailable."
        val (lat, lon) = latLon
        val label = locationName ?: "your location"

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,precipitation,weathercode,windspeed_10m" +
            "&temperature_unit=celsius&windspeed_unit=kmh&timezone=auto"

        val responseText = runCatching {
            val request = Request.Builder().url(url).get().build()
            externalHttp.newCall(request).execute().use { it.body?.string() ?: "" }
        }.getOrElse { return "Weather fetch failed: ${it.message}" }

        val data = runCatching {
            json.decodeFromString(OpenMeteoResponse.serializer(), responseText)
        }.getOrElse { return "Weather parse failed: ${it.message}" }

        val c = data.current
        val conditions = weatherCodeToDescription(c.weatherCode)
        return "Weather in $label: ${c.temperature.toInt()}°C, $conditions. " +
            "Feels like ${c.feelsLike.toInt()}°C. " +
            "Wind ${c.windSpeed.toInt()} km/h." +
            if (c.precipitation > 0) " Precipitation: ${c.precipitation} mm." else ""
    }

    private fun fetchNearby(type: String, radiusMeters: Int, locationName: String?): String {
        val (latLon, error) = resolveLatLon(locationName)
        if (latLon == null) return error ?: "Location unavailable."
        val (lat, lon) = latLon
        val label = locationName ?: "your location"

        val amenityFilter = when (type) {
            "restaurant"   -> "restaurant|fast_food|food_court"
            "gas_station"  -> "fuel"
            "coffee"       -> "cafe"
            "parking"      -> "parking"
            "pharmacy"     -> "pharmacy"
            "supermarket"  -> "supermarket|convenience"
            else           -> "restaurant"
        }

        val query = "[out:json][timeout:10];" +
            "node(around:$radiusMeters,$lat,$lon)[amenity~\"^($amenityFilter)$\"];" +
            "out 8;"

        val responseText = runCatching {
            val body = FormBody.Builder().add("data", query).build()
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(body)
                .build()
            externalHttp.newCall(request).execute().use { it.body?.string() ?: "" }
        }.getOrElse { return "Nearby search failed: ${it.message}" }

        val elements = runCatching {
            json.decodeFromString(OverpassResponse.serializer(), responseText).elements
        }.getOrElse { return "Nearby parse failed: ${it.message}" }

        val typeLabel = type.replace('_', ' ').replaceFirstChar { it.uppercase() }
        if (elements.isEmpty()) return "No ${typeLabel}s found near $label within ${radiusMeters / 1000} km."

        val results = elements
            .filter { it.tags["name"] != null && it.lat != null && it.lon != null }
            .take(5)
            .mapIndexed { i, el ->
                val dist = haversineDistanceMiles(lat, lon, el.lat!!, el.lon!!)
                val name = el.tags["name"] ?: "Unknown"
                "${i + 1}. $name (${String.format("%.1f", dist)} mi)"
            }

        return if (results.isEmpty()) "No named ${typeLabel}s found near $label."
        else "${typeLabel}s near $label: ${results.joinToString(", ")}"
    }

    private fun fetchTransit(destination: String, originOverride: String?): String {
        if (googleMapsApiKey.isBlank()) {
            return "No Google Maps API key set — add one in Settings to use transit info."
        }

        // Resolve origin: explicit override (must be a real place name), or current GPS
        val vagueOriginPhrases = setOf("current location", "my location", "here", "current position", "gps")
        val origin = if (originOverride != null && originOverride.lowercase() !in vagueOriginPhrases) {
            originOverride
        } else {
            val gps = currentLatLon() ?: return "Location unavailable — cannot determine origin."
            "${gps.first},${gps.second}"
        }

        val departureTime = System.currentTimeMillis() / 1000
        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${Uri.encode(origin)}" +
            "&destination=${Uri.encode(destination)}" +
            "&mode=transit" +
            "&departure_time=$departureTime" +
            "&key=$googleMapsApiKey"

        Log.d(TAG, "Transit request: origin=$origin dest=$destination key=${googleMapsApiKey.take(8)}…")

        val responseText = runCatching {
            val request = Request.Builder().url(url).get().build()
            externalHttp.newCall(request).execute().use { it.body?.string() ?: "" }
        }.getOrElse { return "Transit fetch failed: ${it.message}" }

        Log.d(TAG, "Transit response: $responseText")

        val response = runCatching {
            json.decodeFromString(DirectionsResponse.serializer(), responseText)
        }.getOrElse { return "Transit parse failed: ${it.message}" }

        if (response.status != "OK") {
            val detail = response.errorMessage?.let { " — $it" } ?: ""
            return when (response.status) {
                "ZERO_RESULTS" -> "No transit routes found from ${originOverride ?: "your location"} to $destination."
                "NOT_FOUND"    -> "Could not find origin or destination."
                else           -> "Transit lookup failed: ${response.status}$detail"
            }
        }

        val leg = response.routes.firstOrNull()?.legs?.firstOrNull()
            ?: return "No route data returned."

        val totalDuration = leg.duration.text
        val departureStr = leg.departureTime?.text ?: "unknown"
        val arrivalStr = leg.arrivalTime?.text ?: "unknown"

        val transitSteps = leg.steps.filter { it.travelMode == "TRANSIT" }
        if (transitSteps.isEmpty()) return "No transit steps found in route."

        val summary = buildString {
            append("Trip to $destination ($totalDuration): ")
            transitSteps.forEachIndexed { i, step ->
                val td = step.transitDetails ?: return@forEachIndexed
                val lineName = td.line.shortName ?: td.line.name ?: td.line.vehicle.name
                val vehicleType = td.line.vehicle.name
                if (i == 0) {
                    append("Take $vehicleType \"$lineName\" from ${td.departureStop.name}")
                    append(" at ${td.departureTime.text}")
                } else {
                    append(", transfer to $vehicleType \"$lineName\" at ${td.departureStop.name}")
                    append(" (${td.departureTime.text})")
                }
                if (td.numStops > 0) append(" — ${td.numStops} stops")
            }
            val lastTransit = transitSteps.last().transitDetails
            if (lastTransit != null) append(", arrive ${lastTransit.arrivalStop.name}")
            append(" at $arrivalStr.")
        }

        return summary
    }

    private fun navigateTo(destination: String): String {
        val encoded = Uri.encode(destination)

        // Try Waze first
        if (isAppInstalled("com.waze")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=$encoded&navigate=yes"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            return "Navigating to \"$destination\" via Waze."
        }

        // Fall back to Google Maps navigation intent
        val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return if (mapsIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapsIntent)
            "Navigating to \"$destination\" via Google Maps."
        } else {
            // Last resort: open maps search
            openUrl("https://www.google.com/maps/search/?q=$encoded")
            "Opened Google Maps search for \"$destination\"."
        }
    }

    private fun weatherCodeToDescription(code: Int) = when (code) {
        0           -> "clear sky"
        1, 2        -> "partly cloudy"
        3           -> "overcast"
        45, 48      -> "foggy"
        51, 53, 55  -> "drizzle"
        61, 63, 65  -> "rain"
        66, 67      -> "freezing rain"
        71, 73, 75  -> "snow"
        77          -> "snow grains"
        80, 81, 82  -> "rain showers"
        85, 86      -> "snow showers"
        95          -> "thunderstorm"
        96, 99      -> "thunderstorm with hail"
        else        -> "mixed conditions"
    }

    private fun haversineDistanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3958.8 // Earth radius in miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }

    /** Debug helper — fires a real Directions API call without needing the agent. */
    suspend fun testTransit(destination: String): String =
        withContext(Dispatchers.IO) { fetchTransit(destination, null) }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isAppInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
