package lt.kpukas.dragonradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.Sensor
import android.view.SensorEvent
import android.view.SensorEventListener
import android.view.SensorManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity(), LocationListener, SensorEventListener {

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var radarView: RadarView
    private lateinit var statusText: TextView
    private lateinit var countText: TextView

    private var currentLocation: Location? = null
    private var headingDegrees = 0f
    private val targets = mutableListOf<RadarTarget>()
    private val preferences by lazy { getSharedPreferences("dragon_radar", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) startGps() else {
                statusText.text = "GPS leidimas nesuteiktas"
                Toast.makeText(this, "Radaro veikimui reikia vietos leidimo", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        loadTargets()
        buildScreen()
        startCompass()

        if (hasLocationPermission()) startGps() else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.rgb(5, 20, 12))
        }

        val title = TextView(this).apply {
            text = "DRAGON RADAR"
            textSize = 28f
            setTextColor(Color.rgb(90, 255, 120))
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            text = "Ieškoma GPS signalo..."
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        countText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(255, 220, 80))
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }
        root.addView(countText, LinearLayout.LayoutParams(-1, -2))

        radarView = RadarView(this)
        root.addView(
            radarView,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val addButton = Button(this).apply {
            text = "PAŽYMĖTI RUTULIO VIETĄ"
            setOnClickListener { addTargetHere() }
        }
        root.addView(addButton, LinearLayout.LayoutParams(-1, -2))

        val clearButton = Button(this).apply {
            text = "IŠTRINTI VISUS RUTULIUS"
            setOnClickListener {
                targets.clear()
                saveTargets()
                updateScreen()
                Toast.makeText(this@MainActivity, "Visi rutuliai ištrinti", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearButton, LinearLayout.LayoutParams(-1, -2))

        val help = TextView(this).apply {
            text = "Pažymėk vietą, nueik bent 20 m ir grįžk į ją – taip surinksi rutulį."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 0)
        }
        root.addView(help, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        updateScreen()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("MissingPermission")
    private fun startGps() {
        if (!hasLocationPermission()) return
        try {
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) onLocationChanged(last)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 1f, this
            )
            statusText.text = "GPS įjungtas – ieškoma palydovų..."
        } catch (e: Exception) {
            statusText.text = "Nepavyko paleisti GPS: ${e.localizedMessage ?: "klaida"}"
        }
    }

    private fun startCompass() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        var changed = false

        val iterator = targets.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next()
            val distance = distanceTo(target)
            if (distance > 20f) target.hasMovedAway = true
            if (target.hasMovedAway && distance <= 15f) {
                iterator.remove()
                changed = true
                Toast.makeText(this, "Surinkai drakono rutulį! ✨", Toast.LENGTH_SHORT).show()
            }
        }

        if (changed) saveTargets()
        statusText.text = "GPS tikslumas: apie ${location.accuracy.toInt()} m"
        updateScreen()
    }

    private fun addTargetHere() {
        val location = currentLocation
        if (location == null) {
            Toast.makeText(this, "Dar laukiama GPS signalo", Toast.LENGTH_SHORT).show()
            return
        }
        if (targets.size >= 7) {
            Toast.makeText(this, "Jau pažymėti 7 rutuliai", Toast.LENGTH_SHORT).show()
            return
        }
        targets.add(RadarTarget(location.latitude, location.longitude))
        saveTargets()
        updateScreen()
        Toast.makeText(
            this,
            "Vieta pažymėta. Nueik bent 20 m ir sugrįžk!",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun distanceTo(target: RadarTarget): Float {
        val here = currentLocation ?: return Float.MAX_VALUE
        val result = FloatArray(1)
        Location.distanceBetween(
            here.latitude, here.longitude,
            target.latitude, target.longitude,
            result
        )
        return result[0]
    }

    private fun updateScreen() {
        if (!::countText.isInitialized) return
        countText.text = "RADARE: ${targets.size} / 7"
        radarView.invalidate()
    }

    private fun saveTargets() {
        val array = JSONArray()
        targets.forEach {
            array.put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)
                .put("away", it.hasMovedAway))
        }
        preferences.edit().putString("targets", array.toString()).apply()
    }

    private fun loadTargets() {
        val raw = preferences.getString("targets", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                targets.add(
                    RadarTarget(
                        item.getDouble("lat"),
                        item.getDouble("lon"),
                        item.optBoolean("away", false)
                    )
                )
            }
        } catch (_: Exception) {
            targets.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val matrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            SensorManager.getOrientation(matrix, orientation)
            headingDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            radarView.invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        statusText.text = "Įjunk telefono vietos nustatymus (GPS)"
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onPause() {
        super.onPause()
        if (::locationManager.isInitialized && hasLocationPermission()) {
            locationManager.removeUpdates(this)
        }
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        startCompass()
        if (::locationManager.isInitialized && hasLocationPermission()) startGps()
    }

    private inner class RadarView(context: Context) : View(context) {
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 180, 85)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 255, 120)
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(130, 255, 150)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * 0.43f
            canvas.drawColor(Color.rgb(2, 15, 8))

            for (fraction in floatArrayOf(0.25f, 0.5f, 0.75f, 1f)) {
                canvas.drawCircle(cx, cy, radius * fraction, gridPaint)
            }
            canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint)

            val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(35, 80, 255, 120)
                style = Paint.Style.FILL
            }
            canvas.drawArc(
                cx - radius, cy - radius, cx + radius, cy + radius,
                -headingDegrees - 35f, 70f, true, sweepPaint
            )

            val here = currentLocation
            if (here != null) {
                targets.forEachIndexed { index, target ->
                    val results = FloatArray(2)
                    Location.distanceBetween(
                        here.latitude, here.longitude,
                        target.latitude, target.longitude,
                        results
                    )
                    val distance = results[0]
                    val bearing = results[1]
                    val relative = Math.toRadians((bearing - headingDegrees).toDouble())
                    val radarDistance = (distance / 500f).coerceAtMost(1f) * radius
                    val x = cx + sin(relative).toFloat() * radarDistance
                    val y = cy - cos(relative).toFloat() * radarDistance
                    canvas.drawCircle(x, y, 11f, dotPaint)
                    canvas.drawText("${index + 1}", x, y - 17f, textPaint)
                }
            }

            canvas.drawCircle(cx, cy, 9f, glowPaint)
            canvas.drawText("N", cx, cy - radius - 12f, textPaint)
            canvas.drawText("500 m", cx, cy + radius + 30f, textPaint)
        }
    }

    private data class RadarTarget(
        val latitude: Double,
        val longitude: Double,
        var hasMovedAway: Boolean = false
    )
}
