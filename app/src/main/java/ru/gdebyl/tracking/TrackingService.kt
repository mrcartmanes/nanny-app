package ru.gdebyl.tracking

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.gdebyl.GdeBylApp
import ru.gdebyl.MainActivity
import ru.gdebyl.R
import ru.gdebyl.data.LocationPoint
import java.util.Locale

class TrackingService : Service() {
    private val app get() = application as GdeBylApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val points = Channel<LocationPoint>(Channel.UNLIMITED)
    private val lifecycleMutex = Mutex()
    private lateinit var client: FusedLocationProviderClient
    @Volatile private var stopping = false
    private var running = false
    private var interval = 0L
    private val resolving = mutableSetOf<Long>()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.sortedBy { it.time }.forEach { l ->
                if (l.hasAccuracy() && l.time <= System.currentTimeMillis() + 10_000)
                    points.trySend(LocationPoint(ts = l.time, lat = l.latitude, lon = l.longitude, accuracyM = l.accuracy))
            }
        }
        override fun onLocationAvailability(value: LocationAvailability) {
            if (!value.isLocationAvailable) app.preferences.error(getString(R.string.signal_wait))
        }
    }
    override fun onCreate() {
        super.onCreate()
        channels(this)
        client = LocationServices.getFusedLocationProviderClient(this)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finishRecording(); return START_NOT_STICKY }
        if (stopping) return START_NOT_STICKY
        if (running) return START_STICKY
        running = true
        if (!app.preferences.value.recording && intent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startForeground(1, notification(getString(R.string.finding_place)))
        } catch (_: RuntimeException) {
            app.preferences.recording(false)
            app.preferences.error(getString(R.string.start_failed))
            stopSelf(); return START_NOT_STICKY
        }
        app.preferences.recording(true)
        app.preferences.error(null)
        scope.launch {
            try {
                if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this@TrackingService) != ConnectionResult.SUCCESS)
                    throw IllegalStateException("Play Services unavailable")
                lifecycleMutex.withLock {
                    if (stopping) return@launch
                    app.detector.begin(System.currentTimeMillis())
                }
                request(90_000)
                for (point in points) {
                    if (stopping) break
                    val stationary = app.detector.point(point)
                    app.preferences.error(null)
                    request(if (stationary) 300_000 else 90_000)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                app.preferences.error(getString(R.string.location_failed))
                finishRecording()
            }
        }
        scope.launch {
            while (isActive && !stopping) {
                app.detector.expire(System.currentTimeMillis())
                delay(60_000)
            }
        }
        scope.launch {
            app.database.diary().observeVisits().collect { visits ->
                if (stopping) return@collect
                val active = visits.lastOrNull { it.visit.endTs == null }
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(1, notification(active?.place?.name ?: getString(R.string.finding_place)))
                visits.map { it.place }.distinctBy { it.id }.filter { it.address == null && it.id !in resolving }.forEach { place ->
                    resolving.add(place.id)
                    launch {
                        val address = withTimeoutOrNull(12_000) { reverseGeocode(place.lat, place.lon) }
                        if (!address.isNullOrBlank()) app.database.diary().address(place.id, address, getString(R.string.unnamed))
                    }
                }
            }
        }
        return START_STICKY
    }
    @Suppress("MissingPermission")
    private suspend fun request(millis: Long) {
        if (interval == millis || stopping) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Precise location required")
        client.removeLocationUpdates(callback)
        interval = millis
        withContext(Dispatchers.Main) {
            client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, millis)
                .setMinUpdateIntervalMillis(60_000).setMaxUpdateDelayMillis(millis).build(), callback, Looper.getMainLooper())
                .addOnFailureListener { app.preferences.error(getString(R.string.location_failed)); finishRecording() }
        }
    }
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val coder = Geocoder(this, Locale("ru"))
            val address = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine<android.location.Address?> { continuation ->
                    coder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull()) { _, _, _ -> }
                        }
                        override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resume(null) { _, _, _ -> } }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                coder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
            address?.let { a -> listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(", ").ifBlank { a.getAddressLine(0) } }
        } catch (_: Exception) { null }
    }
    private fun finishRecording() {
        if (stopping) return
        stopping = true
        client.removeLocationUpdates(callback)
        scope.launch {
            try { lifecycleMutex.withLock { app.detector.stop(System.currentTimeMillis()) } }
            finally { withContext(Dispatchers.Main) { app.preferences.recording(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
    }
    private fun notification(place: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(getString(R.string.recording_notification)).setContentText(place).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.stop), stop).build()
    }
    override fun onDestroy() { client.removeLocationUpdates(callback); points.close(); scope.cancel(); super.onDestroy() }
    companion object {
        const val ACTION_STOP = "ru.gdebyl.STOP"
        const val CHANNEL = "tracking"
        fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("resume", context.getString(R.string.resume_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as GdeBylApp
        if (!app.preferences.value.recording) return
        TrackingService.channels(context)
        // Opening the activity is a valid user initiated route on modern Android.
        val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java).putExtra("resume", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            context.getSystemService(NotificationManager::class.java).notify(2, NotificationCompat.Builder(context, "resume")
                .setSmallIcon(R.drawable.ic_pin).setContentTitle(context.getString(R.string.resume_title))
                .setContentText(context.getString(R.string.resume_text)).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
