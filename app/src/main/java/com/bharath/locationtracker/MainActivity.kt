package com.bharath.locationtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Ready")
    private var locationText by mutableStateOf("No location fetched yet")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        status = if (granted) "Location permission granted" else "Location permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TrackerScreen(
                    status = status,
                    location = locationText,
                    onGrantPermission = { requestLocationPermission() },
                    onFetchLocation = { fetchLocation() }
                )
            }
        }
    }

    private fun requestLocationPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status = "Please grant location permission first"
            return
        }

        status = "Fetching location..."
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation
            .addOnSuccessListener { location ->
                if (location == null) {
                    status = "Location unavailable. Turn on GPS and try again."
                } else {
                    locationText = String.format(
                        Locale.US,
                        "Latitude: %.6f\nLongitude: %.6f\nAccuracy: %.1f m",
                        location.latitude,
                        location.longitude,
                        location.accuracy
                    )
                    status = "Location fetched successfully"
                }
            }
            .addOnFailureListener { error -> status = "Location error: ${error.message}" }
    }
}

@androidx.compose.runtime.Composable
private fun TrackerScreen(
    status: String,
    location: String,
    onGrantPermission: () -> Unit,
    onFetchLocation: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Location Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("Status: $status")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Current Location", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(location)
            }
        }

        Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Grant Location Permission")
        }
        Button(onClick = onFetchLocation, modifier = Modifier.fillMaxWidth()) {
            Text("Fetch Location Now")
        }

        Spacer(Modifier.height(8.dp))
        Text("Firebase tracking, remote commands and configurable intervals will be added next.")
    }
}
