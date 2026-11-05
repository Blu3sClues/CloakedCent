package com.cloakedcent.app.ui.feature.receipt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.cloakedcent.app.domain.model.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * One location fix, only when the user asks for it. Uses the platform location
 * service (no Google Play services, no network calls from this app) and stops
 * listening as soon as a fix arrives or [TIMEOUT_MILLIS] passes. Never tracks.
 */
object StoreLocation {

    private const val TIMEOUT_MILLIS = 30_000L

    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // checked by hasPermission
    suspend fun capture(context: Context): GeoPoint? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val location = withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { fix: Location? ->
                        if (continuation.isActive) continuation.resume(fix)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(fix: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(fix)
                        }

                        @Deprecated("Required on API < 29")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        } ?: return null
        return GeoPoint((location.latitude * 1e7).roundToLong(), (location.longitude * 1e7).roundToLong())
    }
}
