
package lt.kpukas.dragonradar

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var creatorView: CreatorView

    private var selectedBall = 4
    private var currentLocation: Location? = null
    private var mapDialog: Dialog? = null
    private var mapView: MapView? = null
    private var ballMarker: Marker? = null
    private var myLocationOverlay: Overlay? = null
    private var firstMapLocation = false
    private var selectedBallAlreadySaved = false
    private var radarDialog: Dialog? = null
    private var radarView: RadarView? = null

    private val preferences by lazy {
        getSharedPreferences("dragon_radar_creator", Context.MODE_PRIVATE)
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasLocationPermission()) {
                openGpsMap()
            } else {
                Toast.makeText(
                    this,
                    "Reikia vietos nustatymo leidimo.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        creatorView = CreatorView(this)
        setContentView(creatorView)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestGpsPermission() {
        if (hasLocationPermission()) {
            openGpsMap()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @Suppress("MissingPermission")
    private fun openGpsMap() {
        try {
            currentLocation = null
            firstMapLocation = false

            val providers = locationManager.getProviders(true)

            for (provider in providers) {
                val last = locationManager.getLastKnownLocation(provider)

                if (last != null &&
                    (currentLocation == null ||
                            last.time > currentLocation!!.time)
                ) {
                    currentLocation = last
                }
            }

            showMapDialog()

            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        2000L,
                        2f,
                        this
                    )
                } catch (_: Exception) {
                }
            }

            currentLocation?.let {
                updateMapLocation(it)
            }

            Toast.makeText(
                this,
                "Nustatoma tavo GPS vieta...",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "GPS klaida: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadSavedBallLocations(): Map<Int, GeoPoint> {
        val raw = preferences.getString("balls", "[]") ?: "[]"
        val result = mutableMapOf<Int, GeoPoint>()

        try {
            val array = JSONArray(raw)

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val number = item.optInt("number", -1)

                if (number in 1..7) {
                    val lat = item.optDouble("latitude")
                    val lon = item.optDouble("longitude")

                    if (lat.isFinite() && lon.isFinite() &&
                        lat in -90.0..90.0 &&
                        lon in -180.0..180.0
                    ) {
                        result[number] = GeoPoint(lat, lon)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return result
    }

    private fun loadFoundBallNumbers(): Set<Int> {
        val raw = preferences.getString("found_balls", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optInt(index, -1).takeIf { it in 1..7 }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun markBallFound(number: Int) {
        if (number !in 1..7) return
        val found = loadFoundBallNumbers().toMutableSet()
        found.add(number)
        val array = JSONArray()
        found.sorted().forEach { array.put(it) }
        preferences.edit().putString("found_balls", array.toString()).apply()
    }

    private fun createBallMarkerIcon(
        number: Int,
        diameterDp: Int = 44
    ): android.graphics.drawable.Drawable {
        val density = resources.displayMetrics.density
        val size = (diameterDp * density).toInt()

        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        val radius = size * 0.39f

        paint.color = Color.rgb(255, 155, 0)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, radius, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        canvas.drawCircle(center, center, radius, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = diameterDp * 0.48f * density
        paint.typeface = Typeface.DEFAULT_BOLD

        val baseline = center - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(number.toString(), center, baseline, paint)

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun showMapDialog() {
        val dialog = Dialog(this)
        mapDialog = dialog

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "PAŽYMĖK $selectedBall ŽVAIGŽDUČIŲ RUTULĮ"
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }

        val info = TextView(this).apply {
            text = "Mėlynas taškas – tavo GPS vieta. Oranžinį žymeklį gali perkelti. Kiti oranžiniai žymekliai – jau pažymėti rutuliai."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }

        val map = MapView(this)
        mapView = map

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)

        val savedLocations = loadSavedBallLocations()
        selectedBallAlreadySaved = savedLocations.containsKey(selectedBall)

        val gpsPoint = currentLocation?.let {
            GeoPoint(it.latitude, it.longitude)
        } ?: GeoPoint(54.8985, 23.9036)

        val startPoint = savedLocations[selectedBall] ?: gpsPoint

        map.controller.setCenter(startPoint)

        // Rodome visus kitus jau pažymėtus rutulius žemėlapyje.
        for ((number, point) in savedLocations) {
            if (number == selectedBall) continue

            val savedMarker = Marker(map).apply {
                position = point
                icon = createBallMarkerIcon(number)

                setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
                )

                this.title = "$number žvaigždučių rutulys"
                snippet = "Šis rutulys jau pažymėtas"
            }

            map.overlays.add(savedMarker)
        }

        // Dabartinio pasirinkto rutulio žymeklis.
        val marker = Marker(map).apply {
            position = startPoint
            icon = createBallMarkerIcon(selectedBall, 60)

            this.title = "$selectedBall žvaigždučių rutulys"
            snippet = "Laikyk nuspaudęs ir perkelk žymeklį"

            setAnchor(
                Marker.ANCHOR_CENTER,
                Marker.ANCHOR_BOTTOM
            )

            setDraggable(true)

            setOnMarkerDragListener(
                object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) {}

                    override fun onMarkerDragEnd(marker: Marker) {
                        info.text =
                            "Rutulio žymeklis perkeltas. Patikrink vietą ir patvirtink."
                    }

                    override fun onMarkerDragStart(marker: Marker) {}
                }
            )
        }

        ballMarker = marker
        map.overlays.add(marker)

        // Mėlynas GPS taškas su trumpu išsiplėtimo impulsu.
        var gpsPulse = 0f
        val gpsPulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                gpsPulse = it.animatedValue as Float
                map.invalidate()
            }
        }
        val blueOverlay = object : Overlay() {

            private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 145, 255)
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }

            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 120, 255)
                style = Paint.Style.FILL
            }

            private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }

            override fun draw(
                canvas: Canvas,
                mapView: MapView,
                shadow: Boolean
            ) {
                super.draw(canvas, mapView, shadow)

                if (shadow) return

                val location = currentLocation ?: return

                val point = mapView.projection.toPixels(
                    GeoPoint(location.latitude, location.longitude),
                    null
                )

                pulsePaint.alpha = (190f * (1f - gpsPulse)).toInt()
                pulsePaint.strokeWidth = 5f * (1f - gpsPulse) + 1f
                canvas.drawCircle(
                    point.x.toFloat(),
                    point.y.toFloat(),
                    13f + 34f * gpsPulse,
                    pulsePaint
                )

                canvas.drawCircle(
                    point.x.toFloat(),
                    point.y.toFloat(),
                    13f,
                    paint
                )

                canvas.drawCircle(
                    point.x.toFloat(),
                    point.y.toFloat(),
                    13f,
                    border
                )
            }
        }

        myLocationOverlay = blueOverlay
        map.overlays.add(blueOverlay)
        gpsPulseAnimator.start()

        val mapParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        layout.addView(title)
        layout.addView(info)
        layout.addView(map, mapParams)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val myLocationButton = Button(this).apply {
            text = "⌖  MANO GPS VIETA"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)

            backgroundTintList = ColorStateList.valueOf(
                Color.rgb(25, 105, 220)
            )

            setOnClickListener {
                currentLocation?.let { location ->
                    map.controller.animateTo(
                        GeoPoint(location.latitude, location.longitude)
                    )
                } ?: Toast.makeText(
                    this@MainActivity,
                    "Dar laukiama GPS signalo.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val confirmButton = Button(this).apply {
            text = "✓  PATVIRTINTI RUTULIO VIETĄ"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)

            backgroundTintList = ColorStateList.valueOf(
                Color.rgb(235, 125, 0)
            )

            setOnClickListener {
                val point = ballMarker?.position

                if (point == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Nėra pasirinktos vietos.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    saveSelectedBallLocation(
                        point.latitude,
                        point.longitude
                    )

                    stopLocationUpdates()
                    dialog.dismiss()

                    Toast.makeText(
                        this@MainActivity,
                        "$selectedBall žvaigždučių rutulio vieta išsaugota!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val buttonHeight =
            (58 * resources.displayMetrics.density).toInt()

        buttons.addView(
            myLocationButton,
            LinearLayout.LayoutParams(0, buttonHeight, 1f)
        )

        buttons.addView(
            confirmButton,
            LinearLayout.LayoutParams(0, buttonHeight, 1f)
        )

        layout.addView(buttons)

        dialog.setContentView(layout)

        dialog.setOnDismissListener {
            stopLocationUpdates()
            mapView = null
            ballMarker = null
            myLocationOverlay = null
            mapDialog = null
        }

        dialog.show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            (resources.displayMetrics.heightPixels * 0.90f).toInt()
        )

        map.invalidate()
    }

    private fun updateMapLocation(location: Location) {
        currentLocation = location

        runOnUiThread {
            val map = mapView ?: return@runOnUiThread

            if (!firstMapLocation) {
                firstMapLocation = true

                val point = GeoPoint(
                    location.latitude,
                    location.longitude
                )

                if (!selectedBallAlreadySaved) {
                    map.controller.animateTo(point)
                    ballMarker?.position = point
                }
            }

            map.invalidate()
            radarView?.invalidate()
        }
    }

    override fun onLocationChanged(location: Location) {
        updateMapLocation(location)
    }

    private fun stopLocationUpdates() {
        try {
            if (hasLocationPermission()) {
                locationManager.removeUpdates(this)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveSelectedBallLocation(
        latitude: Double,
        longitude: Double
    ) {
        val raw = preferences.getString("balls", "[]") ?: "[]"

        val oldArray = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }

        val saved = mutableMapOf<Int, JSONObject>()

        for (i in 0 until oldArray.length()) {
            val item = oldArray.optJSONObject(i) ?: continue
            val number = item.optInt("number", -1)

            if (number in 1..7) {
                saved[number] = item
            }
        }

        saved[selectedBall] = JSONObject()
            .put("number", selectedBall)
            .put("latitude", latitude)
            .put("longitude", longitude)

        val newArray = JSONArray()

        for (number in 1..7) {
            saved[number]?.let {
                newArray.put(it)
            }
        }

        preferences.edit()
            .putString("balls", newArray.toString())
            .apply()

        creatorView.invalidate()
    }

    private fun selectBall(number: Int) {
        selectedBall = number
        creatorView.invalidate()

        Toast.makeText(
            this,
            "Pasirinktas $number žvaigždučių rutulys",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showRadarScreen() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        radarDialog = dialog
        val view = RadarView(this)
        radarView = view
        dialog.setContentView(view)
        if (hasLocationPermission()) {
            try {
                // Pirmiausia panaudojame paskutinę žinomą GPS vietą,
                // kad radaras neliktų tuščias, kol ateis naujas GPS matavimas.
                for (provider in locationManager.getProviders(true)) {
                    try {
                        val last = locationManager.getLastKnownLocation(provider)
                        if (last != null &&
                            (currentLocation == null || last.time > currentLocation!!.time)
                        ) {
                            currentLocation = last
                        }
                    } catch (_: SecurityException) {
                    }
                }

                for (provider in locationManager.getProviders(true)) {
                    try {
                        locationManager.requestLocationUpdates(provider, 1000L, 1f, this)
                    } catch (_: SecurityException) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        dialog.setOnDismissListener {
            radarDialog = null
            radarView = null
            stopLocationUpdates()
        }
        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private inner class RadarView(context: Context) : View(context), SensorEventListener {
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        private var deviceAzimuth = 0f
        private val rotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var scanAngle = -90f
        private var entryGlow = 0f
        // Keturi mastelio lygiai: kiekvienas paspaudimas priartina aplink žaidėją.
        // GPS koordinatės nekinta – keičiasi tik radaro atvaizdavimo mastelis.
        private val zoomRanges = floatArrayOf(200f, 50f, 10f, 2f)
        private var zoomLevel = 0
        private val radarRangeMeters: Float
            get() = zoomRanges[zoomLevel]
        // Valdiklius pakeliame aukščiau, kad jie nebūtų nukerpami ekrano apačioje.
        private val zoomButtonRect: RectF
            get() = RectF(width * 0.06f, height - 285f, width * 0.53f, height - 205f)
        private val backButtonRect: RectF
            get() = RectF(width * 0.57f, height - 285f, width * 0.94f, height - 205f)
        // Originalus trumpas radaro pyptelėjimas, kartojamas aptikus išsaugotą rutulį.
        private val radarTone = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
        private var lastSignalBeepAt = 0L
        private var nearestUnfoundBallNumber = 0
        private val foundDistanceMeters = 5f
        private val foundButtonRect: RectF
            get() = RectF(width * 0.18f, height - 390f, width * 0.82f, height - 315f)
        private val entryAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 620L
            addUpdateListener {
                entryGlow = it.animatedValue as Float
                invalidate()
            }
        }
        private val scanAnimator = ValueAnimator.ofFloat(-90f, 270f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                scanAngle = it.animatedValue as Float
                invalidate()
            }
        }
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        init {
            rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            animator.start()
            entryAnimator.start()
            scanAnimator.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(3, 20, 12))
            val cx = width / 2f
            val cy = height * 0.47f
            val r = minOf(width * 0.43f, height * 0.37f)
            paint.color = Color.rgb(10, 65, 28)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r + 20f, paint)
            paint.shader = RadialGradient(cx - r * .2f, cy - r * .25f, r * 1.5f,
                intArrayOf(Color.rgb(105, 205, 95), Color.rgb(15, 95, 42), Color.rgb(2, 35, 18)),
                null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            val clip = canvas.save()
            val circle = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            canvas.clipPath(circle)
            paint.color = Color.argb(95, 190, 255, 170)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            val step = r / 12f
            var x = cx - r
            while (x <= cx + r) { canvas.drawLine(x, cy-r, x, cy+r, paint); x += step }
            var y = cy - r
            while (y <= cy + r) { canvas.drawLine(cx-r, y, cx+r, y, paint); y += step }
            paint.color = Color.argb(190, 190, 255, 170)
            paint.strokeWidth = 3f
            for (i in 1..4) canvas.drawCircle(cx, cy, r * i / 4f, paint)
            canvas.drawLine(cx-r, cy, cx+r, cy, paint)
            canvas.drawLine(cx, cy-r, cx, cy+r, paint)
            // Trumpas, ryškus žalios spalvos sužibėjimas atidarant radarą.
            if (entryGlow > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 10f + 12f * entryGlow
                paint.color = Color.argb((150f * entryGlow).toInt(), 150, 255, 115)
                paint.setShadowLayer(24f * entryGlow, 0f, 0f, Color.GREEN)
                canvas.drawCircle(cx, cy, r + 8f, paint)
                paint.clearShadowLayer()
                paint.style = Paint.Style.FILL
            }
            val sweepStart = Math.toRadians((scanAngle - 55f).toDouble())
            val sweep = Path().apply {
                moveTo(cx, cy)
                lineTo(
                    cx + kotlin.math.cos(sweepStart).toFloat() * r,
                    cy + kotlin.math.sin(sweepStart).toFloat() * r
                )
                arcTo(
                    RectF(cx-r, cy-r, cx+r, cy+r),
                    scanAngle - 55f, 55f
                )
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(75, 190, 255, 130)
            canvas.drawPath(sweep, paint)
            // Greitai apsisukanti skenavimo linija.
            val scanRadians = Math.toRadians(scanAngle.toDouble())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = Color.argb(235, 205, 255, 165)
            paint.setShadowLayer(16f, 0f, 0f, Color.GREEN)
            canvas.drawLine(
                cx, cy,
                cx + kotlin.math.cos(scanRadians).toFloat() * r,
                cy + kotlin.math.sin(scanRadians).toFloat() * r,
                paint
            )
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
            // Taikinio krypties rodyklė ir mirksintis rutulio signalas.
            paint.color = Color.RED
            val arrowHalfWidth = 5f
            val arrowHeight = arrowHalfWidth * 4f // bendras aukštis 20 px, plotis 10 px
            val arrowTop = cy - r * .04f
            val arrow = Path().apply {
                moveTo(cx, arrowTop)
                lineTo(cx-arrowHalfWidth, arrowTop + arrowHeight)
                lineTo(cx, arrowTop + arrowHeight * .70f)
                lineTo(cx+arrowHalfWidth, arrowTop + arrowHeight)
                close()
            }
            paint.setShadowLayer(7f, 0f, 0f, Color.RED)
            canvas.drawPath(arrow, paint)
            paint.clearShadowLayer()
            // Pažymėtų rutulių signalai: kryptis ir atstumas skaičiuojami pagal GPS.
            val userLocation = currentLocation
            val savedBalls = loadSavedBallLocations()
            val foundBalls = loadFoundBallNumbers()
            var nearestDistance = Float.MAX_VALUE
            var visibleCount = 0
            nearestUnfoundBallNumber = 0
            if (userLocation != null) {
                for ((number, geoPoint) in savedBalls) {
                    if (number in foundBalls) continue
                    val target = Location("dragon_ball").apply {
                        latitude = geoPoint.latitude
                        longitude = geoPoint.longitude
                    }
                    val distance = userLocation.distanceTo(target)
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearestUnfoundBallNumber = number
                    }
                    if (distance <= radarRangeMeters) {
                        val bearing = Math.toRadians(userLocation.bearingTo(target).toDouble() - Math.toDegrees(deviceAzimuth.toDouble()))
                        val normalizedDistance = (distance / radarRangeMeters).coerceIn(0f, 1f)
                        val dotRadius = r * normalizedDistance
                        val dotX = cx + kotlin.math.sin(bearing).toFloat() * dotRadius
                        val dotY = cy - kotlin.math.cos(bearing).toFloat() * dotRadius
                        paint.style = Paint.Style.FILL
                        paint.color = Color.rgb(255, 175, 25)
                        paint.setShadowLayer(15f, 0f, 0f, Color.YELLOW)
                        canvas.drawCircle(dotX, dotY, 9f + 3f * kotlin.math.abs(kotlin.math.sin(phase * Math.PI).toFloat()), paint)
                        paint.clearShadowLayer()
                        paint.color = Color.rgb(255, 245, 190)
                        paint.textAlign = Paint.Align.LEFT
                        paint.textSize = 20f
                        canvas.drawText(number.toString(), dotX + 12f, dotY - 8f, paint)
                        visibleCount++
                    }
                }
            }
            canvas.restoreToCount(clip)

            // Pyptelėjimas tik tada, kai bent vienas išsaugotas rutulys patenka į radaro zoną.
            // Ribojame dažnį, kad garsas būtų aiškus ir neerzintų.
            val now = SystemClock.elapsedRealtime()
            if (visibleCount > 0 && now - lastSignalBeepAt >= 850L) {
                radarTone.startTone(ToneGenerator.TONE_PROP_BEEP, 105)
                lastSignalBeepAt = now
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            paint.textSize = 30f
            canvas.drawText("DRAGON RADAR", cx, 58f, paint)
            paint.textSize = 20f
            val status = when {
                userLocation == null -> "GPS: WAITING FOR LOCATION"
                savedBalls.isEmpty() -> "NO DRAGON BALLS SAVED"
                foundBalls.size == 7 -> "ALL 7 DRAGON BALLS FOUND!"
                visibleCount == 0 -> "FOUND ${foundBalls.size}/7  •  NO SIGNAL"
                else -> "FOUND ${foundBalls.size}/7  •  ${visibleCount} SIGNAL(S)"
            }
            val closeToBall = nearestUnfoundBallNumber != 0 &&
                nearestDistance <= foundDistanceMeters
            canvas.drawText(status, cx, if (closeToBall) height-445f else height-330f, paint)
            paint.textSize = 18f
            paint.color = Color.rgb(185, 255, 190)
            canvas.drawText(
                "RADIJUS  ${formatRadarDistance(radarRangeMeters)}  •  ${zoomLevel + 1}/4",
                cx,
                if (closeToBall) height-415f else height-300f,
                paint
            )
            // Surasti rutuliai kaupiasi prie rodyklės; septintasis uždedamas tiksliai ant jos viršaus.
            drawCollectedDragonBalls(canvas, cx, arrowTop, foundBalls)
            if (closeToBall) drawFoundButton(canvas)
            drawRadarControl(canvas, zoomButtonRect, "＋  PRIARTINTI", true)
            drawRadarControl(canvas, backButtonRect, "←  GRĮŽTI", false)
        }

        private fun drawCollectedDragonBalls(
            canvas: Canvas,
            arrowCenterX: Float,
            arrowTop: Float,
            foundBalls: Set<Int>
        ) {
            if (foundBalls.isEmpty()) return

            // Kiekvienas rastas rutulys rodomas savo vietoje aplink rodyklę.
            // Rutulio viduje žvaigždutės išdėstomos taip pat kaip pagrindiniame ekrane.
            val positions = arrayOf(
                Pair(-17f, -15f), Pair(-17f, 5f), Pair(-17f, 25f),
                Pair(17f, -15f), Pair(17f, 5f), Pair(17f, 25f),
                Pair(0f, 0f)
            )
            val radius = 10.5f
            val scale = radius / 86f
            val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(135, 35, 0)
                style = Paint.Style.FILL
            }
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 235, 135)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }

            for (number in 1..7) {
                if (number !in foundBalls) continue
                val centerX = arrowCenterX + positions[number - 1].first
                val centerY = arrowTop + positions[number - 1].second
                ballPaint.shader = RadialGradient(
                    centerX - 4f, centerY - 5f, radius * 1.8f,
                    intArrayOf(
                        Color.rgb(255, 250, 155),
                        Color.rgb(255, 165, 15),
                        Color.rgb(220, 65, 0)
                    ), null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(centerX, centerY, radius, ballPaint)
                ballPaint.shader = null
                canvas.drawCircle(centerX, centerY, radius, rimPaint)

                val starPositions = when (number) {
                    1 -> listOf(Pair(0f, 0f))
                    2 -> listOf(Pair(-22f, 0f), Pair(22f, 0f))
                    3 -> listOf(Pair(0f, -24f), Pair(-22f, 18f), Pair(22f, 18f))
                    4 -> listOf(Pair(-22f, -22f), Pair(22f, -22f), Pair(-22f, 22f), Pair(22f, 22f))
                    5 -> listOf(Pair(0f, -30f), Pair(-29f, -8f), Pair(29f, -8f), Pair(-18f, 27f), Pair(18f, 27f))
                    6 -> listOf(Pair(-25f, -25f), Pair(25f, -25f), Pair(-25f, 0f), Pair(25f, 0f), Pair(-25f, 25f), Pair(25f, 25f))
                    else -> listOf(Pair(0f, -34f), Pair(-29f, -17f), Pair(29f, -17f), Pair(-34f, 14f), Pair(0f, 5f), Pair(34f, 14f), Pair(0f, 36f))
                }

                starPositions.forEach { offset ->
                    drawMiniDragonStar(
                        canvas,
                        centerX + offset.first * scale,
                        centerY + offset.second * scale,
                        19f * scale,
                        starPaint
                    )
                }
            }
        }

        private fun drawMiniDragonStar(
            canvas: Canvas,
            centerX: Float,
            centerY: Float,
            radius: Float,
            paint: Paint
        ) {
            val path = Path()
            val innerRadius = radius * 0.43f
            for (i in 0 until 10) {
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val r = if (i % 2 == 0) radius else innerRadius
                val px = centerX + cos(angle).toFloat() * r
                val py = centerY + sin(angle).toFloat() * r
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        private fun drawFoundButton(canvas: Canvas) {
            val rect = foundButtonRect
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    intArrayOf(
                        Color.rgb(255, 235, 95),
                        Color.rgb(255, 145, 15),
                        Color.rgb(205, 65, 0)
                    ), null, Shader.TileMode.CLAMP
                )
                setShadowLayer(16f, 0f, 5f, Color.rgb(255, 135, 0))
            }
            canvas.drawRoundRect(rect, 24f, 24f, fill)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRoundRect(rect, 24f, 24f, border)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 29f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setShadowLayer(3f, 1f, 2f, Color.BLACK)
            }
            canvas.drawText(
                "★  RADU! PAŽYMĖTI RASTU ★",
                rect.centerX(),
                rect.centerY() - (label.ascent() + label.descent()) / 2f,
                label
            )
        }

        private fun drawRadarControl(canvas: Canvas, rect: RectF, label: String, zoom: Boolean) {
            val radius = 22f
            val colors = if (zoom) intArrayOf(
                Color.rgb(175, 255, 95), Color.rgb(45, 175, 35), Color.rgb(15, 95, 25)
            ) else intArrayOf(
                Color.rgb(75, 125, 185), Color.rgb(20, 55, 105), Color.rgb(5, 20, 55)
            )
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                setShadowLayer(12f, 0f, 5f, Color.BLACK)
            }
            canvas.drawRoundRect(rect, radius, radius, shadow)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    colors, null, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = if (zoom) Color.rgb(120, 255, 65) else Color.rgb(105, 160, 230)
            }
            canvas.drawRoundRect(rect, radius, radius, border)
            val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.argb(100, 255, 255, 255)
            }
            canvas.drawRoundRect(RectF(rect.left + 5f, rect.top + 4f,
                rect.right - 5f, rect.centerY()), radius - 5f, radius - 5f, shine)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = if (zoom) 17f else 19f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setShadowLayer(3f, 1f, 2f, Color.BLACK)
            }
            canvas.drawText(label, rect.centerX(),
                rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                deviceAzimuth = orientationAngles[0]
                invalidate()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                when {
                    foundButtonRect.contains(event.x, event.y) &&
                        nearestUnfoundBallNumber != 0 -> {
                        val number = nearestUnfoundBallNumber
                        markBallFound(number)
                        nearestUnfoundBallNumber = 0
                        Toast.makeText(
                            this@MainActivity,
                            "$number žvaigždučių rutulys pažymėtas kaip rastas!",
                            Toast.LENGTH_LONG
                        ).show()
                        invalidate()
                    }
                    zoomButtonRect.contains(event.x, event.y) -> {
                        zoomLevel = (zoomLevel + 1) % zoomRanges.size
                        invalidate()
                    }
                    backButtonRect.contains(event.x, event.y) -> radarDialog?.dismiss()
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            sensorManager.unregisterListener(this)
            animator.cancel()
            entryAnimator.cancel()
            scanAnimator.cancel()
            radarTone.stopTone()
            radarTone.release()
            super.onDetachedFromWindow()
        }
    }

    private fun formatRadarDistance(meters: Float): String =
        if (meters < 1000f) "${meters.toInt()} m" else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1000f)

    private fun showSettings() {
        Toast.makeText(
            this,
            "Žaidimo nustatymai bus prijungti kitame etape.",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        Toast.makeText(
            this,
            "Įjunk telefono vietos nustatymus.",
            Toast.LENGTH_SHORT
        ).show()
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(
        provider: String?,
        status: Int,
        extras: Bundle?
    ) {}

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        mapView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        mapView?.onDetach()
        super.onDestroy()
    }

    private inner class CreatorView(context: Context) : View(context) {

        private val backgroundBitmap: Bitmap? = try {
            BitmapFactory.decodeResource(
                resources,
                R.drawable.dragon_radar_creator
            )
        } catch (_: Exception) {
            null
        }

        private val ballCenters = arrayOf(
            Pair(259f, 390f),
            Pair(764f, 390f),
            Pair(259f, 700f),
            Pair(512f, 535f),
            Pair(764f, 700f),
            Pair(355f, 870f),
            Pair(670f, 870f)
        )

        private val ballRadius = 86f

        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 225, 80)
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }

        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(155, 24, 0)
            style = Paint.Style.FILL
        }

        private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 220)
            style = Paint.Style.FILL
        }

        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val gpsButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 145, 75)
            style = Paint.Style.FILL
        }

        private val gpsButtonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val gpsButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 34f
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }

        private var pressedButton = 0
        private var buttonPressScale = 1f
        private var buttonGlow = 0f
        private var buttonGlowAnimator: ValueAnimator? = null
        private var scale = 1f
        private var scaleY = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Kraštus užpildome tuo pačiu paveikslėliu, tik švelniai
            // išplėstu ir sulietu – nelieka matomos mėlynos juostos ar siūlės.
            val bitmap = backgroundBitmap
            if (bitmap != null && width > 0 && height > 0) {
                val backdropPaint = Paint(
                    Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
                ).apply {
                    alpha = 105
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        floatArrayOf(
                            0.72f, 0f, 0f, 0f, 0f,
                            0f, 0.72f, 0f, 0f, 0f,
                            0f, 0f, 0.82f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                }
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    backdropPaint
                )
                canvas.drawColor(Color.argb(95, 5, 16, 38))
            } else {
                canvas.drawColor(Color.rgb(12, 48, 105))
            }

            // Pagrindinis paveikslėlis visada telpa visas, išlaikant proporcijas.
            // Naujas vertikalus maketas užpildo visą ekraną.
            // Koordinatės skaičiuojamos 1024 × 1792 projekto erdvėje.
            scale = width / 1024f
            scaleY = height / 1792f
            offsetX = 0f
            offsetY = 0f

            val save = canvas.save()

            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scaleY)

            if (bitmap != null) {
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(0f, 0f, 1024f, 1792f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }

            // Radaro mygtukas perkeliamas į buvusią GPS mygtuko vietą.
            drawActionButton(
                canvas,
                RectF(55f, 1460f, 490f, 1635f),
                "RADARAS",
                Color.rgb(85, 205, 35),
                true
            )

            // GPS žymėjimas lieka apačioje, oranžinio paveikslėlio mygtuko vietoje.
            drawActionButton(
                canvas,
                RectF(534f, 1460f, 969f, 1635f),
                "ŽYMĖTI PAGAL GPS",
                Color.rgb(255, 153, 0),
                false
            )

            for (number in 1..7) {
                drawDragonBall(canvas, number)
            }

            canvas.restoreToCount(save)
        }

        private fun drawActionButton(
            canvas: Canvas,
            rect: RectF,
            label: String,
            baseColor: Int,
            radarIcon: Boolean
        ) {
            val radius = 30f
            val buttonId = if (radarIcon) 1 else 2
            val buttonCanvasSave = canvas.save()
            if (pressedButton == buttonId && buttonGlow > 0f) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (radarIcon) Color.argb((145f * buttonGlow).toInt(), 75, 255, 45)
                    else Color.argb((155f * buttonGlow).toInt(), 255, 145, 20)
                    style = Paint.Style.STROKE
                    strokeWidth = 12f + 10f * buttonGlow
                    setShadowLayer(26f * buttonGlow, 0f, 0f, color)
                }
                canvas.drawRoundRect(
                    RectF(rect.left - 5f, rect.top - 5f, rect.right + 5f, rect.bottom + 5f),
                    radius + 8f, radius + 8f, glow
                )
            }
            if (pressedButton == buttonId) {
                canvas.scale(buttonPressScale, buttonPressScale, rect.centerX(), rect.centerY())
            }
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(185, 0, 0, 0)
                style = Paint.Style.FILL
                setShadowLayer(12f, 0f, 7f, Color.BLACK)
            }
            canvas.drawRoundRect(rect, radius, radius, shadow)

            val colors = if (radarIcon) {
                intArrayOf(
                    Color.rgb(190, 255, 95),
                    baseColor,
                    Color.rgb(35, 145, 20)
                )
            } else {
                intArrayOf(
                    Color.rgb(255, 220, 75),
                    baseColor,
                    Color.rgb(215, 85, 0)
                )
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    colors, null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, radius, radius, fill)

            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (radarIcon) Color.rgb(20, 95, 15) else Color.rgb(145, 55, 0)
                style = Paint.Style.STROKE
                strokeWidth = 9f
            }
            canvas.drawRoundRect(rect, radius, radius, rim)

            val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(105, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            canvas.drawRoundRect(
                RectF(rect.left + 9f, rect.top + 7f, rect.right - 9f, rect.bottom - 8f),
                radius - 7f, radius - 7f, shine
            )

            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (radarIcon) Color.rgb(15, 75, 15) else Color.rgb(105, 43, 0)
                style = Paint.Style.STROKE
                strokeWidth = 12f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val cx = rect.left + 62f
            val cy = rect.centerY()

            if (radarIcon) {
                // Mažas klasikinio Dragon Radar ekrano ženklas:
                // tamsus korpusas, žalias ekranas, oranžiniai rutulių taškai.
                val screenX = cx - 8f
                val screenY = cy
                val screenRadius = 43f

                val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(8, 35, 12)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(screenX, screenY, screenRadius + 9f, casing)

                val screenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        screenX - 12f, screenY - 14f, screenRadius * 1.7f,
                        intArrayOf(
                            Color.rgb(205, 255, 150),
                            Color.rgb(105, 195, 65),
                            Color.rgb(30, 105, 25)
                        ),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(screenX, screenY, screenRadius, screenFill)

                val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(190, 15, 85, 20)
                    style = Paint.Style.STROKE
                    strokeWidth = 2.8f
                }
                canvas.drawCircle(screenX, screenY, 14f, grid)
                canvas.drawCircle(screenX, screenY, 28f, grid)
                canvas.drawLine(
                    screenX - screenRadius, screenY,
                    screenX + screenRadius, screenY, grid
                )
                canvas.drawLine(
                    screenX, screenY - screenRadius,
                    screenX, screenY + screenRadius, grid
                )

                val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(100, 205, 255, 130)
                    style = Paint.Style.FILL
                }
                val sweepPath = Path().apply {
                    moveTo(screenX, screenY)
                    lineTo(screenX, screenY - screenRadius)
                    arcTo(
                        RectF(
                            screenX - screenRadius,
                            screenY - screenRadius,
                            screenX + screenRadius,
                            screenY + screenRadius
                        ),
                        -90f, 72f
                    )
                    close()
                }
                canvas.drawPath(sweepPath, sweep)

                val ballDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 125, 0)
                    style = Paint.Style.FILL
                }
                val ballDotRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 225, 120)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val dots = listOf(
                    Pair(screenX + 19f, screenY - 19f),
                    Pair(screenX - 22f, screenY + 17f),
                    Pair(screenX + 8f, screenY + 29f)
                )
                dots.forEach { dot ->
                    canvas.drawCircle(dot.first, dot.second, 5.5f, ballDot)
                    canvas.drawCircle(dot.first, dot.second, 5.5f, ballDotRim)
                }

                val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(5, 55, 12)
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }
                canvas.drawCircle(screenX, screenY, screenRadius + 2f, rim)
            } else {
                // GPS vietos žymeklis.
                val pin = Path().apply {
                    moveTo(cx, cy + 42f)
                    cubicTo(cx - 12f, cy + 22f, cx - 36f, cy - 1f, cx - 36f, cy - 22f)
                    cubicTo(cx - 36f, cy - 69f, cx + 36f, cy - 69f, cx + 36f, cy - 22f)
                    cubicTo(cx + 36f, cy - 1f, cx + 12f, cy + 22f, cx, cy + 42f)
                    close()
                }
                iconPaint.style = Paint.Style.FILL
                canvas.drawPath(pin, iconPaint)
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 190, 35)
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(cx, cy - 25f, 12f, hole)
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (radarIcon) Color.rgb(10, 65, 10) else Color.rgb(100, 38, 0)
                textAlign = Paint.Align.CENTER
                textSize = if (label.length > 15) 25f else 34f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setShadowLayer(1.5f, 1f, 2f, Color.argb(80, 0, 0, 0))
            }
            val textX = rect.centerX() + 30f
            val textY = rect.centerY() -
                (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, textX, textY, textPaint)
            canvas.restoreToCount(buttonCanvasSave)
        }

        private fun animateButtonPress(buttonId: Int, pressed: Boolean) {
            pressedButton = buttonId
            val from = buttonPressScale
            val to = if (pressed) 0.94f else 1f
            ValueAnimator.ofFloat(from, to).apply {
                duration = if (pressed) 75L else 155L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    buttonPressScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            buttonGlowAnimator?.cancel()
            buttonGlowAnimator = ValueAnimator.ofFloat(
                if (pressed) 0f else buttonGlow,
                if (pressed) 1f else 0f
            ).apply {
                duration = if (pressed) 190L else 260L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    buttonGlow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun drawDragonBall(canvas: Canvas, number: Int) {
            val center = ballCenters[number - 1]
            val x = center.first
            val y = center.second

            ballPaint.shader = RadialGradient(
                x - 25f,
                y - 30f,
                ballRadius * 1.5f,
                intArrayOf(
                    Color.rgb(255, 245, 145),
                    Color.rgb(255, 177, 15),
                    Color.rgb(238, 91, 0)
                ),
                null,
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(x, y, ballRadius, ballPaint)

            ballPaint.shader = null

            canvas.drawCircle(
                x,
                y,
                ballRadius,
                edgePaint
            )

            canvas.drawOval(
                x - 62f,
                y - 66f,
                x - 27f,
                y - 30f,
                shinePaint
            )

            val positions = when (number) {
                1 -> listOf(Pair(0f, 0f))

                2 -> listOf(
                    Pair(-22f, 0f),
                    Pair(22f, 0f)
                )

                3 -> listOf(
                    Pair(0f, -24f),
                    Pair(-22f, 18f),
                    Pair(22f, 18f)
                )

                4 -> listOf(
                    Pair(-22f, -22f),
                    Pair(22f, -22f),
                    Pair(-22f, 22f),
                    Pair(22f, 22f)
                )

                5 -> listOf(
                    Pair(0f, -30f),
                    Pair(-29f, -8f),
                    Pair(29f, -8f),
                    Pair(-18f, 27f),
                    Pair(18f, 27f)
                )

                6 -> listOf(
                    Pair(-25f, -25f),
                    Pair(25f, -25f),
                    Pair(-25f, 0f),
                    Pair(25f, 0f),
                    Pair(-25f, 25f),
                    Pair(25f, 25f)
                )

                else -> listOf(
                    Pair(0f, -34f),
                    Pair(-29f, -17f),
                    Pair(29f, -17f),
                    Pair(-34f, 14f),
                    Pair(0f, 5f),
                    Pair(34f, 14f),
                    Pair(0f, 36f)
                )
            }

            positions.forEach {
                drawStar(
                    canvas,
                    x + it.first,
                    y + it.second,
                    19f
                )
            }

            if (selectedBall == number) {
                canvas.drawCircle(
                    x,
                    y,
                    ballRadius + 9f,
                    selectedPaint
                )
            }
        }

        private fun drawStar(
            canvas: Canvas,
            centerX: Float,
            centerY: Float,
            radius: Float
        ) {
            val path = Path()
            val innerRadius = radius * 0.43f

            for (i in 0 until 10) {
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val r = if (i % 2 == 0) radius else innerRadius

                val px = centerX + cos(angle).toFloat() * r
                val py = centerY + sin(angle).toFloat() * r

                if (i == 0) {
                    path.moveTo(px, py)
                } else {
                    path.lineTo(px, py)
                }
            }

            path.close()
            canvas.drawPath(path, starPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (scale <= 0f) return true
            val x = (event.x - offsetX) / scale
            val y = (event.y - offsetY) / scaleY

            if (event.action == MotionEvent.ACTION_DOWN) {
                pressedButton = when {
                    x in 55f..490f && y in 1460f..1635f -> 1
                    x in 534f..969f && y in 1460f..1635f -> 2
                    else -> 0
                }
                if (pressedButton != 0) animateButtonPress(pressedButton, true)
                return true
            }

            if (event.action == MotionEvent.ACTION_UP) {
                val pressed = pressedButton
                if (pressed != 0) animateButtonPress(pressed, false)

                for (number in 1..7) {
                    val center = ballCenters[number - 1]
                    val dx = x - center.first
                    val dy = y - center.second
                    if (dx * dx + dy * dy <= ballRadius * ballRadius) {
                        selectBall(number)
                        return true
                    }
                }

                if (pressed == 1 && x in 55f..490f && y in 1460f..1635f) {
                    showRadarScreen()
                    return true
                }
                if (pressed == 2 && x in 534f..969f && y in 1460f..1635f) {
                    requestGpsPermission()
                    return true
                }
                if (x < 145f && y < 145f) finish()
                return true
            }

            if (event.action == MotionEvent.ACTION_CANCEL) {
                if (pressedButton != 0) animateButtonPress(pressedButton, false)
            }
            return true
        }
    }
}
