package lt.kpukas.dragonradar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.view.Gravity
import android.widget.Toast
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var radar: RadarView
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(0, 24, 8)
        window.navigationBarColor = Color.BLACK
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0, 12, 4))
            setPadding(20, 24, 20, 20)
        }
        val title = TextView(this).apply {
            text = "DRAGON RADAR"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(80, 255, 120))
            letterSpacing = 0.18f
        }
        status = TextView(this).apply {
            text = "GPS: laukiama leidimo"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(130, 255, 160))
            setPadding(0, 12, 0, 12)
        }
        radar = RadarView(this)
        val note = TextView(this).apply {
            text = "Radaro taikinio koordinatės dar nenustatytos. GPS rodo tavo buvimo vietą."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 210, 125))
            setPadding(8, 12, 8, 8)
        }
        val gpsButton = Button(this).apply {
            text = "ĮJUNGTI GPS"
            setOnClickListener { requestGps() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(radar, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(note)
        root.addView(gpsButton)
        setContentView(root)
        requestGps()
    }

    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            return
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, listener, Looper.getMainLooper())
            status.text = "GPS: ieškoma palydovų…"
        } catch (_: Exception) {
            status.text = "GPS nepasiekiamas. Patikrink vietos nustatymus."
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            status.text = "GPS: %.5f, %.5f  •  tikslumas %.0f m".format(location.latitude, location.longitude, location.accuracy)
            radar.invalidate()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) requestGps()
        else status.text = "GPS leidimas nesuteiktas"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
    }

    inner class RadarView(context: android.content.Context) : View(context) {
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 180, 65); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 255, 120) }
        private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 75, 28); style = Paint.Style.FILL
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) * 0.43f
            canvas.drawColor(Color.rgb(0, 12, 4))
            for (i in 1..4) canvas.drawCircle(cx, cy, r * i / 4f, grid)
            canvas.drawLine(cx-r, cy, cx+r, cy, grid)
            canvas.drawLine(cx, cy-r, cx, cy+r, grid)
            canvas.drawCircle(cx, cy, 5f, glow)
            // Subtle center glow; target location is intentionally not fabricated.
            canvas.drawCircle(cx, cy, 14f, faint)
            canvas.drawCircle(cx, cy, 5f, glow)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(80, 220, 120); textSize = 25f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("GPS RADAR", cx, cy + r + 42f, label)
        }
    }
}
