
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
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

    private var selectedBall = 1
    private var currentLocation: Location? = null
    private var mapDialog: Dialog? = null
    private var mapView: MapView? = null
    private var ballMarker: Marker? = null
    private var myLocationOverlay: Overlay? = null
    private var firstMapLocation = false
    private var selectedBallAlreadySaved = false

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

        // Mėlynas dabartinės GPS vietos taškas.
        val blueOverlay = object : Overlay() {

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
            Pair(252f, 335f),
            Pair(480f, 335f),
            Pair(704f, 335f),
            Pair(930f, 335f),
            Pair(362f, 507f),
            Pair(590f, 507f),
            Pair(820f, 507f)
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

        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Užpildome ekraną, išlaikydami paveikslėlio proporcijas.
            scale = maxOf(
                width / 1184f,
                height / 1280f
            )

            offsetX = (width - 1184f * scale) / 2f
            offsetY = (height - 1280f * scale) / 2f

            val save = canvas.save()

            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)

            if (backgroundBitmap != null) {
                canvas.drawBitmap(
                    backgroundBitmap,
                    null,
                    RectF(0f, 0f, 1184f, 1280f),
                    Paint(Paint.ANTI_ALIAS_FLAG)
                )
            } else {
                canvas.drawColor(Color.rgb(12, 48, 105))
            }

            // Vienas aiškus GPS žymėjimo mygtukas.
            val buttonRect = RectF(
                176f,
                760f,
                1008f,
                850f
            )

            canvas.drawRoundRect(
                buttonRect,
                22f,
                22f,
                gpsButtonPaint
            )

            canvas.drawRoundRect(
                buttonRect,
                22f,
                22f,
                gpsButtonBorderPaint
            )

            val buttonText = "PAŽYMĖTI PAGAL GPS"
            val textY = buttonRect.centerY() -
                    (gpsButtonTextPaint.ascent() +
                            gpsButtonTextPaint.descent()) / 2f

            canvas.drawText(
                buttonText,
                buttonRect.centerX(),
                textY,
                gpsButtonTextPaint
            )

            for (number in 1..7) {
                drawDragonBall(canvas, number)
            }

            canvas.restoreToCount(save)
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
            if (event.action != MotionEvent.ACTION_UP) {
                return true
            }

            if (scale <= 0f) return true

            val x = (event.x - offsetX) / scale
            val y = (event.y - offsetY) / scale

            // Rutulių pasirinkimas.
            for (number in 1..7) {
                val center = ballCenters[number - 1]
                val dx = x - center.first
                val dy = y - center.second

                if (dx * dx + dy * dy <= ballRadius * ballRadius) {
                    selectBall(number)
                    return true
                }
            }

            // GPS mygtukas.
            if (x in 176f..1008f && y in 755f..855f) {
                requestGpsPermission()
                return true
            }

            // Nustatymai.
            if (x > 940f && y < 140f) {
                showSettings()
                return true
            }

            return true
        }
    }
}
