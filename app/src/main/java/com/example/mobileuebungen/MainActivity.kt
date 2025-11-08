package com.example.mobileuebungen

import RaplaParser
import RaplaResult
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.time.LocalDate
import com.google.gson.Gson

private val gson = Gson()
private val JSON_FILE_NAME = "rapla_events.json"
class MainActivity : ComponentActivity() {

    // PendingIntent to deliver geofence transitions to the BroadcastReceiver
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    // ActivityResultLaunchers must be registered before the Activity is STARTED
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var calendarPermissionLauncher: ActivityResultLauncher<Array<String>>

    // Callback holders to bridge the launcher callbacks with our request helper methods
    private var locationAccessCallback: ((Boolean) -> Unit)? = null
    private var backgroundAccessCallback: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val volleyRequestQueue =
            Volley.newRequestQueue(this) // request queue for http calls with volley

        // Register ActivityResultLaunchers
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fine = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
            val coarse = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)

            if (fine && coarse) {
                Log.d("MainActivity", "Fine and coarse location access granted.")
                // Now request background location permission
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                Log.d("MainActivity", "No location access granted.")
                locationAccessCallback?.invoke(false)
                locationAccessCallback = null
            }
        }

        backgroundLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d("MainActivity", "Background location access granted.")
            } else {
                Log.d("MainActivity", "Background location access denied.")
            }
            locationAccessCallback?.invoke(isGranted)
            backgroundAccessCallback?.invoke(isGranted)
            locationAccessCallback = null
            backgroundAccessCallback = null
        }

        calendarPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            when {
                permissions.getOrDefault(Manifest.permission.WRITE_CALENDAR, false) -> {
                    Log.d("MainActivity", "Write access granted.")
                }
                permissions.getOrDefault(Manifest.permission.READ_CALENDAR, false) -> {
                    Log.d("MainActivity", "Read access granted.")
                }
                else -> {
                    Log.d("MainActivity", "No calendar access granted.")
                }
            }
        }

        // Initialize geofencing (will request permissions if needed)
        initializeGeofencing()

        enableEdgeToEdge()

        enableEdgeToEdge()
        setContent {
            var status by remember { mutableStateOf(Status.READY) }
            val url =
                "https://rapla.dhbw.de/rapla/calendar?key=SF8qHSuYFD3SStyfcj4vvmAhUMdwoDn7AYC1DTtyyBmhFJAv8m_hIYVHpm9Ul6nMjqX11N94dkWx78kCdoJxR44ru1kegzIBOMCCSJVRikkSTGNCV0YyThLBR30y9hOaGryjvwt1kpad5g93Dkdn0A&salt=-218630611"
            val storedRaplaResult = loadRaplaEventsFromJson() ?: RaplaResult(emptyList())
            Log.d("MainActivity", "Loaded stored events: ${storedRaplaResult.allEventTitles()}")
            var raplaResult by remember { mutableStateOf<RaplaResult?>(storedRaplaResult) }

            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 0.dp)
            ) {
                Row( // Top Row with Title
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Upcoming Events",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 15.dp)
                    )
                }
                Column( // Event List
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val events = raplaResult?.weeks?.flatMap { it.events }
                        ?.filter { LocalDate.now() <= it.date }?.sortedBy { it.date }
                        ?: emptyList()
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(events) { event ->
                            EventListItem(
                                event.title,
                                event.course,
                                event.date,
                                event.startTime,
                                event.endTime,
                                event.room ?: "No location specified",
                            )
                        }
                    }
                }
                //Lower Action Buttons
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .padding(7.dp)
                        .padding(bottom = 16.dp)
                        .fillMaxWidth()
                ) {
                    if (status != Status.SUCCESS) {
                        Button(
                            onClick = {
                                status = Status.FETCHING
                                val stringRequest = StringRequest(Request.Method.GET, url, { html ->
                                    val raplaParser = RaplaParser()
                                    raplaResult = raplaParser.parse(html)
                                    raplaResult?.let { storeRaplaEventsAsJson(it) }
                                    Log.d(
                                        "MainActivity",
                                        "Parsed Event Titles: ${raplaResult?.allEventTitles()}"
                                    )
                                    status = Status.SUCCESS
                                }, { error ->
                                    status = Status.ERROR
                                    Log.e("MainActivity", "Error fetching data: $error")
                                })
                                volleyRequestQueue.add(stringRequest)
                            },
                            enabled = status != Status.FETCHING
                        ) {
                            Text(
                                when (status) {
                                    Status.READY -> "Fetch Rapla Events"
                                    Status.FETCHING -> "Fetching..."
                                    Status.ERROR -> "Fetch failed. Retry?"
                                    Status.SUCCESS -> "Refetch Rapla Events"
                                }
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                requestCalendarAccess()
                            }
                        ) { Text("Add events to Calendar") }
                    }

                }
            }
        }
    }

    private fun initializeGeofencing() {
        requestLocationAccess { granted ->
            if (!granted) {
                Log.d("MainActivity", "Location permission not granted - cannot initialize geofencing")
                return@requestLocationAccess
            }

            // Verify permissions are actually granted
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e("MainActivity", "Fine location permission not granted")
                return@requestLocationAccess
            }

            val geofencingClient = LocationServices.getGeofencingClient(this)

            val geofence = Geofence.Builder()
                .setRequestId("dhbw-campus")
                .setCircularRegion(49.4738, 8.5344, 500f) // DHBW Mannheim coordinates
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()

            val geofencingRequest = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()

            try {
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                    .addOnSuccessListener { Log.d("MainActivity", "Geofence added successfully") }
                    .addOnFailureListener { e -> Log.e("MainActivity", "Failed to add geofence", e) }
            } catch (securityException: SecurityException) {
                Log.e("MainActivity", "Missing location permission", securityException)
            }
        }
    }

    private fun requestLocationAccess(onResult: (Boolean) -> Unit) {
        // Save callback and launch the pre-registered launcher
        locationAccessCallback = onResult
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun requestBackgroundLocationAccess(onResult: (Boolean) -> Unit) {
        // Save callback and launch the pre-registered background permission launcher
        backgroundAccessCallback = onResult
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun requestCalendarAccess() {
        // Use the pre-registered calendar launcher
        calendarPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
        )
    }
    // Save RaplaResult persistently to internal storage
    private fun storeRaplaEventsAsJson(raplaResult: RaplaResult) {
        try {
            val json = gson.toJson(raplaResult)
            openFileOutput(JSON_FILE_NAME, MODE_PRIVATE).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            }
            Log.d("MainActivity", "Saved Rapla events to ${filesDir.absolutePath}/$JSON_FILE_NAME")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save Rapla events", e)
        }
    }
    // Load RaplaResult from internal storage (returns null if missing or on error)
    private fun loadRaplaEventsFromJson(): RaplaResult? {
        return try {
            val json = openFileInput(JSON_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            gson.fromJson(json, RaplaResult::class.java)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load Rapla events", e)
            null
        }
    }
}