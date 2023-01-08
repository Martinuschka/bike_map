package com.mcmc.bikemap

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.*
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var mSensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var mLongitude: Double = 0.0
    private var mLatitude: Double = 0.0
    private var oldLong: Double = 0.0
    private var oldLat: Double = 0.0
    private var newLong: Double = 0.0
    private var newLat: Double = 0.0
    private val locResults = FloatArray(1)
    private var distance: Float = 0.0f
    private var speed1: Float = 0.0f
    private var speed2: Float = 0.0f
    private var speed3: Float = 0.0f
    private var mSpeed: Float = 0.0f

    //private val mainHandler = Handler(Looper.getMainLooper())
    lateinit var mainHandler: Handler

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    private val rotationMatrix = FloatArray(9)
    //private val orientationAngles = FloatArray(3)

    private var rotationX: Float=0.0f
    private var rotationY: Float=0.0f
    private var rotationZ: Float=0.0f

    private var measureX: Float=0.0f
    private var measureY: Float=0.0f
    private var measureZ: Float=0.0f

    private var vibration: Float=0.0f
    private var maxVibration: Float=0.0f

    private var running: Boolean=false
    private var showLog: Boolean=false
    private var sending: Boolean=false

    private var countTotal: Int=0
    private var countStand: Int=0
    private var countSmooth: Int=0
    private var countRough: Int=0
    private var countNasty: Int=0

    private val vibrationList: MutableList<String> = mutableListOf()
    private val speedList: MutableList<String> = mutableListOf()
    private val latitudeList: MutableList<String> = mutableListOf()
    private val longitudeList: MutableList<String> = mutableListOf()
    private val timestampList: MutableList<String> = mutableListOf()

    private val apiURL = URL("http://192.168.178.36:5000/bike_map_api")

    private val measureTask = object : Runnable {
        override fun run(){
            if (running) {
                calculateSpeed()
                printValues()
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        getLocationUpdates()

        textVibration.movementMethod = ScrollingMovementMethod()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent){
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                measureX = event.values[0]
                measureY = event.values[1]
                measureZ = event.values[2]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(
                    event.values,
                    0,
                    accelerometerReading,
                    0,
                    accelerometerReading.size
                )
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
            }
        }
        updateOrientationAngles()
    }

    override fun onResume(){
        super.onResume()
        mainHandler.post(measureTask)

        //mSensorManager.registerListener(this,mAccSensors,SensorManager.SENSOR_DELAY_NORMAL)
        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer -> mSensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL,
            SensorManager.SENSOR_DELAY_UI
        ) }
        mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField -> mSensorManager.registerListener(
            this,
            magneticField,
            SensorManager.SENSOR_DELAY_NORMAL,
            SensorManager.SENSOR_DELAY_UI
        )}
        mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.also { linearAcceleration -> mSensorManager.registerListener(
            this,
            linearAcceleration,
            SensorManager.SENSOR_DELAY_NORMAL,
            SensorManager.SENSOR_DELAY_UI
        )}
        startLocationUpdates()

        if (buttonRecord.text==getString(R.string.stopButton)){
            running = true
        }
    }

    override fun onPause(){
        super.onPause()
        mainHandler.removeCallbacks(measureTask)

        mSensorManager.unregisterListener(this)
        stopLocationUpdates()
        running = false
    }

    private fun showToast(toast: String?) {
        runOnUiThread {
            Toast.makeText(applicationContext, toast, Toast.LENGTH_SHORT).show()
        }
    }

    fun buttonRecordClick(@Suppress("UNUSED_PARAMETER") view: View){
        if (!sending) {
            if (!running) {
                //startLocationUpdates()
                resetValues()
                running = true
                buttonRecord.text = getString(R.string.stopButton)
                textVibration.text = ""
                buttonSend.visibility = View.INVISIBLE
                buttonSend.text = getString(R.string.sendData)
            } else {
                running = false
                buttonRecord.text = getString(R.string.startButton)
                buttonSend.text = getString(R.string.sendData)

                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip: ClipData = ClipData.newPlainText("measurements", textVibration.text)
                clipboard.setPrimaryClip(clip)
                showToast("Measurements copied!")

                buttonSend.visibility = View.VISIBLE
                //stopLocationUpdates()
            }
        }
    }

    fun buttonSendDataClick(@Suppress("UNUSED_PARAMETER") view: View){
        if ((countTotal!=0)&&!sending) {
            thread {sendJSONData()}
        }
    }

    fun buttonToggleLogClick(@Suppress("UNUSED_PARAMETER") view: View){
        when (showLog) {
            false -> {
                showLog = true
                headlineVibration.visibility = View.VISIBLE
                textVibration.visibility = View.VISIBLE
                buttonLog.text = getString(R.string.buttonHideLog)
            }
            true -> {
                showLog = false
                headlineVibration.visibility = View.INVISIBLE
                textVibration.visibility = View.INVISIBLE
                buttonLog.text = getString(R.string.buttonShowLog)
            }
        }
    }

    private fun sendJSONData(){
        sending=true
        val queue = Volley.newRequestQueue(this@MainActivity)
        val itemsObject = JSONObject()
        buttonSend.text = getString(R.string.sending)

        for ((index) in vibrationList.withIndex()) {
            itemsObject.put("id","0")
            itemsObject.put("vibration",vibrationList[index])
            itemsObject.put("latitude",latitudeList[index])
            itemsObject.put("longitude",longitudeList[index])
            itemsObject.put("speed",speedList[index])
            itemsObject.put("timestamp",timestampList[index])
            val request = JsonObjectRequest(Request.Method.POST,
                apiURL.toString(), itemsObject, { response ->
                    buttonSend.text = "Finished "+(index+1).toString()+"/"+countTotal.toString()
                    println(response)
                    if (index+1==countTotal){
                        sending=false
                        buttonSend.text=getString(R.string.finished)
                        countTotal=0
                    }
                },
                { error ->
                    println(error)
                    buttonSend.text = "Error at "+(index+1).toString()+"/"+countTotal.toString()
                    if (index+1==countTotal){
                        buttonSend.text=getString(R.string.error)
                        sending=false
                    }
                })
            queue.add(request)
        }

        showToast("Transferring data in Background...")
    }

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )

        rotationX = rotationMatrix[6]
        rotationY = rotationMatrix[7]
        rotationZ = rotationMatrix[8]

        vibration=abs((rotationX * measureX) + (rotationY * measureY) + (rotationZ * measureZ))
        if (vibration>maxVibration) maxVibration=vibration

        //SensorManager.getOrientation(rotationMatrix, orientationAngles)
    }

    private fun calculateSpeed(){
        oldLat = newLat
        oldLong = newLong
        newLat = mLatitude
        newLong = mLongitude

        speed3 = speed2
        speed2 = speed1

        if ((oldLat==0.0) || (oldLong==0.0)) speed1 = 0.0f
        else {
            Location.distanceBetween(oldLat, oldLong, newLat, newLong, locResults)
            distance = locResults[0]
            speed1 = (distance / 1000.0f) * 3600.0f
        }

        mSpeed = ((speed1 * 3.0f) + (speed2 * 2.0f)+ (speed3)) / 6.0f
    }

    private fun getTimeStamp(): String{
        val date= Calendar.getInstance().time
        //val sdf=getDateTimeInstance() //dd.MM.yyyy HH:mm:ss
        val sdf=SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(date)
    }

    private fun printValues(){
        val stamp=getTimeStamp()

        currentSpeed.text="%.0f".format(mSpeed)
        textVibration.append(
            "%.2f \t ".format(maxVibration) + mLatitude.toString() + " \t " + mLongitude.toString() + " \t %.0f \t ".format(
                mSpeed
            ) + stamp + "\n"
        )

        vibrationList.add(maxVibration.toString())
        speedList.add("%.0f".format(mSpeed))
        //speedList.add(mSpeed.toString())
        latitudeList.add(mLatitude.toString())
        longitudeList.add(mLongitude.toString())
        timestampList.add(stamp)

        countTotal+=1

        when {
            maxVibration<1.0f -> countStand += 1
            maxVibration<10.0f -> countSmooth += 1
            maxVibration<30.0f -> countRough += 1
            maxVibration>=30.0f -> countNasty += 1
        }

        headlineVibration.text = "Number of values: $countTotal"
        headlineStand.text = "Standing: $countStand"
        headlineSmooth.text = "Smooth: $countSmooth"
        headlineRough.text = "Rough: $countRough"
        headlineNasty.text = "Nasty: $countNasty"

        progressStand.progress = ((countStand.toFloat()/countTotal.toFloat())*100).toInt()
        progressSmooth.progress = ((countSmooth.toFloat()/countTotal.toFloat())*100).toInt()
        progressRough.progress = ((countRough.toFloat()/countTotal.toFloat())*100).toInt()
        progressNasty.progress = ((countNasty.toFloat()/countTotal.toFloat())*100).toInt()

        maxVibration=0.0f
    }

    private fun resetValues(){
        countTotal = 0
        countStand = 0
        countSmooth = 0
        countRough = 0
        countNasty = 0

        oldLat = 0.0
        oldLong = 0.0
        newLat = 0.0
        newLong = 0.0
        speed1 = 0.0f
        speed2 = 0.0f
        speed3 = 0.0f
        mSpeed = 0.0f
        distance = 0.0f

        vibrationList.clear()
        speedList.clear()
        latitudeList.clear()
        longitudeList.clear()
        timestampList.clear()

        progressStand.progress = 0
        progressSmooth.progress = 0
        progressRough.progress = 0
        progressNasty.progress = 0

        headlineVibration.text = getString(R.string.valuesHeadline)
        headlineStand.text = getString(R.string.standCount)
        headlineSmooth.text = getString(R.string.smoothCount)
        headlineRough.text = getString(R.string.roughCount)
        headlineNasty.text = getString(R.string.nastyCount)
    }

    private fun getLocationUpdates()
    {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create()
        locationRequest.interval = 1000
        locationRequest.fastestInterval = 1000
        //locationRequest.smallestDisplacement = 170f // 170 m = 0.1 mile
        locationRequest.smallestDisplacement = 0.0f
        locationRequest.priority = Priority.PRIORITY_HIGH_ACCURACY
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {

                if (locationResult.locations.isNotEmpty()) {
                    val location = locationResult.lastLocation
                    if (location != null) {
                        mLatitude= location.latitude
                        mLongitude= location.longitude
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                applicationContext,
                "Please authorize app for location!",
                Toast.LENGTH_LONG
            ).show()
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            null
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}