package lt.kpukas.dragonradar
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var radarView: RadarView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        radarView = RadarView()

        setContentView(radarView)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                100
            )
        } else {
            startGPS()
        }
    }

    private fun startGPS() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            2000L,
            1f,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    radarView.updateLocation(
                        location.latitude,
                        location.longitude,
                        location.accuracy
                    )
                }
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startGPS()
        }
    }

    inner class RadarView : View(this@MainActivity) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var latitude = 0.0
        private var longitude = 0.0
        private var accuracy = 0f

        fun updateLocation(lat: Double, lon: Double, acc: Float) {
            latitude = lat
            longitude = lon
            accuracy = acc
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            val radius = min(w, h) * 0.38f
            val cx = w / 2
            val cy = h / 2

            canvas.drawColor(Color.rgb(0, 12, 5))

            paint.color = Color.rgb(0, 255, 80)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f

            for (i in 1..4) {
                canvas.drawCircle(cx, cy, radius * i / 4, paint)
            }

            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

            paint.style = Paint.Style.FILL
            paint.textSize = 32f
            canvas.drawText("DRAGON RADAR", 30f, 55f, paint)

            paint.textSize = 22f
            canvas.drawText(
                "GPS: ${if (latitude == 0.0 && longitude == 0.0) "Ieškoma..." else "Aktyvus"}",
                30f,
                h - 90f,
                paint
            )

            canvas.drawText(
                "Tikslumas: ${accuracy.toInt()} m",
                30f,
                h - 55f,
                paint
            )

            paint.color = Color.RED
            canvas.drawCircle(cx, cy, 9f, paint)
        }
    }
}
