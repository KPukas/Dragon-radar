
package lt.kpukas.dragonradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var creatorView: CreatorView

    private var selectedBall = 1
    private var currentLocation: Location? = null
    private var gpsRequested = false

    private val preferences by lazy {
        getSharedPreferences("dragon_radar_creator", Context.MODE_PRIVATE)
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                startGps()
            } else {
                Toast.makeText(
                    this,
                    "GPS leidimas nesuteiktas",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            startGps()
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
    private fun startGps() {
        if (!hasLocationPermission()) return

        try {
            val providers = locationManager.getProviders(true)

            for (provider in providers) {
                val last = locationManager.getLastKnownLocation(provider)
                if (last != null) {
                    currentLocation = last
                    break
                }
            }

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                1f,
                this
            )

            gpsRequested = true
            creatorView.invalidate()

            Toast.makeText(
                this,
                "Ieškoma GPS vietos...",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "GPS klaida: ${e.localizedMessage ?: "nepavyko įjungti"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        creatorView.invalidate()

        if (gpsRequested) {
            saveSelectedBallLocation()
            gpsRequested = false
            locationManager.removeUpdates(this)

            Toast.makeText(
                this,
                "Pažymėta ${selectedBall} žvaigždutės rutulio vieta!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveSelectedBallLocation() {
        val location = currentLocation ?: return

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
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)

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
            "Pasirinktas ${number} žvaigždutės rutulys",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleMapChoice() {
        Toast.makeText(
            this,
            "Žemėlapio pasirinkimas bus prijungtas kitame etape.",
            Toast.LENGTH_LONG
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

        if (::locationManager.isInitialized && hasLocationPermission()) {
            locationManager.removeUpdates(this)
        }
    }

    override fun onResume() {
        super.onResume()

        if (::locationManager.isInitialized &&
            hasLocationPermission() &&
            gpsRequested
        ) {
            startGps()
        }
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

        // Rutulių centrai bendroje 1184 x 1280 koordinačių sistemoje.
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

        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 153, 0)
            style = Paint.Style.FILL
        }

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

        private var scaleX = 1f
        private var scaleY = 1f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val save = canvas.save()

            // Ekranas ir fonas naudoja vienodą koordinačių sistemą.
            scaleX = width / 1184f
            scaleY = height / 1280f

            canvas.scale(scaleX, scaleY)

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

            // Nupiešiami visi septyni skirtingi rutuliai.
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

            canvas.drawCircle(x, y, ballRadius, edgePaint)

            canvas.drawOval(
                x - 62f,
                y - 66f,
                x - 27f,
                y - 30f,
                shinePaint
            )

            val positions = when (number) {
                1 -> listOf(
                    Pair(0f, 0f)
                )

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

            positions.forEach { position ->
                drawStar(
                    canvas,
                    x + position.first,
                    y + position.second,
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

                val r = if (i % 2 == 0) {
                    radius
                } else {
                    innerRadius
                }

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

            val x = event.x / scaleX
            val y = event.y / scaleY

            // Rutulių pasirinkimas.
            for (number in 1..7) {
                val center = ballCenters[number - 1]
                val dx = x - center.first
                val dy = y - center.second

                if (
                    dx * dx + dy * dy <=
                    ballRadius * ballRadius * 1.5f
                ) {
                    selectBall(number)
                    return true
                }
            }

            // GPS ir žemėlapio pasirinkimai.
            if (y in 755f..855f) {
                if (x < 590f) {
                    requestGpsPermission()
                } else {
                    handleMapChoice()
                }

                return true
            }

            // Apatinis GPS mygtukas.
            if (y in 1100f..1250f) {
                requestGpsPermission()
                return true
            }

            // Nustatymų mygtukas.
            if (x > 940f && y < 140f) {
                showSettings()
                return true
            }

            return true
        }
    }
}
