package com.example.opticalcenter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PermissionWrapper()
                }
            }
        }
    }
}

data class SlamCalibrationInfo(
    val opticalCx: Float, val opticalCy: Float,
    val deltaX: Float, val deltaY: Float,
    val totalAngularDifference: Float, // The factory error in degrees
    val trackingState: String,
    val pitchError: Float,
    val rollError: Float
)

@Composable
fun PermissionWrapper() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }
    if (hasPermission) SlamCameraScreen() else Box(Modifier.fillMaxSize())
}

@Composable
fun SlamCameraScreen() {
    val context = LocalContext.current
    var slamInfo by remember { mutableStateOf<SlamCalibrationInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val glSurfaceView = remember {
        GLSurfaceView(context).apply {
            setPreserveEGLContextOnPause(true)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        }
    }

    DisposableEffect(Unit) {
        var session: Session? = null
        try {
            if (ArCoreApk.getInstance().requestInstall(context as ComponentActivity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                session = Session(context)
                val config = Config(session)
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                session.configure(config)
                session.resume()
            }
        } catch (e: Exception) { errorMessage = e.message }

        glSurfaceView.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                session?.setCameraTextureName(textures[0])
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
                session?.setDisplayGeometry(0, width, height)
            }
            override fun onDrawFrame(gl: GL10?) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                val currentSession = session ?: return
                try {
                    val frame = currentSession.update()
                    val camera = frame.camera

                    // --- 1. OPTICAL CENTER ---
                    val intrinsics = camera.imageIntrinsics
                    val pp = intrinsics.principalPoint
                    val coords = floatArrayOf(pp[0], pp[1])
                    val outCoords = FloatArray(2)
                    frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, coords, Coordinates2d.VIEW, outCoords)

                    val opticalX = outCoords[0]
                    val opticalY = outCoords[1]
                    val screenCx = glSurfaceView.width / 2f
                    val screenCy = glSurfaceView.height / 2f

                    // --- 2. FACTORY TILT CALCULATION (The Real Stuff) ---
                    // Calculate the PHYSICAL rotation from Device Center -> Camera Center
                    // This subtracts the world movement, leaving only the static relationship.
                    val devicePose = frame.androidSensorPose
                    val cameraPose = camera.pose

                    // Math: Offset = Device_Inverse * Camera
                    val deviceToCamera = devicePose.inverse().compose(cameraPose)

                    // Convert to Euler Angles (Degrees)
                    val euler = quaternionToEuler(deviceToCamera)
                    val yaw = euler[0]; val pitch = euler[1]; val roll = euler[2]

                    // Compare to the "Ideal" Integers (Round to nearest 90)
                    // This finds the deviation from the CAD file
                    val idealPitch = (pitch / 90.0).roundToInt() * 90.0
                    val idealRoll = (roll / 90.0).roundToInt() * 90.0
                    val idealYaw = (yaw / 90.0).roundToInt() * 90.0

                    val errPitch = abs(pitch - idealPitch).toFloat()
                    val errRoll = abs(roll - idealRoll).toFloat()
                    val errYaw = abs(yaw - idealYaw).toFloat()

                    // Use the Max error or Root-Sum-Square as the "Total Score"
                    // We ignore Yaw usually because it's just the sensor rotation (landscape/portrait)
                    // But Pitch/Roll are the "Tilt"
                    val totalError = sqrt((errPitch * errPitch) + (errRoll * errRoll))

                    slamInfo = SlamCalibrationInfo(
                        opticalCx = opticalX, opticalCy = opticalY,
                        deltaX = opticalX - screenCx, deltaY = opticalY - screenCy,
                        totalAngularDifference = totalError,
                        trackingState = camera.trackingState.toString(),
                        pitchError = errPitch,
                        rollError = errRoll
                    )

                } catch (e: Exception) { }
            }
        })
        onDispose { session?.pause(); session?.close() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())

        slamInfo?.let { info ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scx = size.width / 2
                val scy = size.height / 2
                // Screen Center (Red)
                drawLine(Color.Red, Offset(scx - 40, scy), Offset(scx + 40, scy), 5f)
                drawLine(Color.Red, Offset(scx, scy - 40), Offset(scx, scy + 40), 5f)
                // Optical Center (Green)
                drawLine(Color.Green, Offset(info.opticalCx - 40, info.opticalCy), Offset(info.opticalCx + 40, info.opticalCy), 5f)
                drawLine(Color.Green, Offset(info.opticalCx, info.opticalCy - 40), Offset(info.opticalCx, info.opticalCy + 40), 5f)
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if(info.trackingState != TrackingState.TRACKING.toString()) {
                    Text("INITIALIZING AR...", color = Color.Yellow)
                }

                Text("FACTORY CAMERA TILT", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "%.3f°".format(info.totalAngularDifference),
                    color = if (info.totalAngularDifference > 0.5f) Color.Red else Color.Green,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Text("Pitch Err: %.3f°".format(info.pitchError), color = Color.White, fontSize = 12.sp)
                    Text("Roll Err: %.3f°".format(info.rollError), color = Color.White, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Optical Center Shift: ${info.deltaX.toInt()}, ${info.deltaY.toInt()} px", color = Color.LightGray, fontSize = 12.sp)
            }
        }
    }
}

// --- MATH HELPER ---
// Converts Quaternion [x,y,z,w] to Euler Degrees [Yaw, Pitch, Roll]
// --- MATH HELPER ---
// Converts Quaternion [x,y,z,w] to Euler Degrees [Yaw, Pitch, Roll]
fun quaternionToEuler(pose: Pose): FloatArray {
    val q = pose.rotationQuaternion
    val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]

    val sinr_cosp = 2 * (w * x + y * z)
    val cosr_cosp = 1 - 2 * (x * x + y * y)
    val roll = atan2(sinr_cosp, cosr_cosp)

    val sinp = 2 * (w * y - z * x)
    // Ensure both branches return Double to avoid type inference issues
    val pitch = if (abs(sinp) >= 1)
        (PI / 2).withSign(sinp.toDouble())
    else
        asin(sinp).toDouble()

    val siny_cosp = 2 * (w * z + x * y)
    val cosy_cosp = 1 - 2 * (y * y + z * z)
    val yaw = atan2(siny_cosp, cosy_cosp)

    return floatArrayOf(
        Math.toDegrees(yaw.toDouble()).toFloat(),    // Added .toDouble()
        Math.toDegrees(pitch).toFloat(),             // pitch is already Double now
        Math.toDegrees(roll.toDouble()).toFloat()    // Added .toDouble()
    )
}