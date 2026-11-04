package io.github.kc3dru.here

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class LocationHelper(private val context: Context) {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    companion object {
        /** A cached fix younger than this is used immediately (no waiting). */
        private const val MAX_CACHE_AGE_MS = 60_000L

        /** How long to wait for a live fix from the provider race. */
        private const val FIX_TIMEOUT_MS = 30_000L
    }

    /**
     * Gets the current location using Android's built-in LocationManager.
     * No Google Play Services needed — works on GrapheneOS.
     *
     * Fast path: if any enabled provider has a last-known fix younger than 60
     * seconds, the most accurate fresh fix is returned immediately without
     * waiting for a new one.
     *
     * Otherwise every enabled provider is raced simultaneously via
     * requestLocationUpdates and the first usable fix wins. Each provider is
     * registered individually so one unusable provider cannot abort the rest.
     * Waits up to 30 seconds for a fix.
     *
     * Works with only ACCESS_COARSE_LOCATION granted (GrapheneOS "Approximate"
     * mode) — a coarse fix still produces a valid Maps link.
     *
     * @throws SecurityException if neither fine nor coarse location permission is granted
     * @throws LocationUnavailableException if no location fix within timeout
     */
    suspend fun getCurrentLocation(): Location {
        if (!hasAnyLocationPermission()) {
            throw SecurityException("Location permission not granted")
        }

        val enabledProviders = locationManager.getProviders(true)
        if (enabledProviders.isEmpty()) {
            throw LocationUnavailableException(
                "No location provider available. Enable GPS in Settings."
            )
        }

        // Fast path: reuse a fresh cached fix if any provider has one
        val freshFixes = mutableListOf<Location>()
        for (provider in enabledProviders) {
            try {
                val last = locationManager.getLastKnownLocation(provider)
                if (last != null &&
                    System.currentTimeMillis() - last.time < MAX_CACHE_AGE_MS
                ) {
                    freshFixes.add(last)
                }
            } catch (e: SecurityException) {
                // Provider requires a permission we do not hold — skip it
            }
        }
        if (freshFixes.isNotEmpty()) {
            // Smaller accuracy value = more accurate fix
            return freshFixes.minByOrNull { it.accuracy }!!
        }

        // No fresh cache — race every enabled provider for a live fix
        val deferred = CompletableDeferred<Location>()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Reject obviously useless fixes (epoch/zero timestamps)
                if (location.time <= 0L) return
                locationManager.removeUpdates(this)
                deferred.complete(location)
            }

            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        }

        // Register each provider individually; one failing provider must not
        // abort the others. Track whether at least one registration succeeded.
        var registeredAny = false
        for (provider in enabledProviders) {
            try {
                locationManager.requestLocationUpdates(
                    provider, 1_000L, 0f, listener, Looper.getMainLooper()
                )
                registeredAny = true
            } catch (e: SecurityException) {
                // Provider requires fine location and we only hold coarse
            } catch (e: IllegalArgumentException) {
                // Unknown provider — ignore and keep racing the rest
            }
        }
        if (!registeredAny) {
            throw LocationUnavailableException(
                "No location provider available. Enable GPS in Settings."
            )
        }

        return try {
            withTimeout(FIX_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw LocationUnavailableException(
                "GPS timed out after 30 seconds. Move outside with a clear view of the sky."
            )
        } catch (e: Exception) {
            throw LocationUnavailableException("Failed to get location: ${e.message}")
        } finally {
            // Deregisters the listener from every provider on every exit path;
            // a no-op if it was already removed on the success path.
            locationManager.removeUpdates(listener)
        }
    }

    /**
     * True if either fine or coarse location permission is granted (this
     * instance's context). Coarse-only is enough for a usable Maps link.
     */
    private fun hasAnyLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Opens the location settings so the user can enable GPS.
     */
    fun openLocationSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        activity.startActivity(intent)
    }

    /**
     * Checks if the activity has any usable location permission
     * (fine OR coarse).
     */
    fun hasLocationPermission(activity: Activity): Boolean {
        return hasFineLocationPermission(activity) ||
            ActivityCompat.checkSelfPermission(
                activity, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if the activity has exact (fine) location permission.
     */
    fun hasFineLocationPermission(activity: Activity): Boolean {
        return ActivityCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class LocationUnavailableException(message: String) : RuntimeException(message)
