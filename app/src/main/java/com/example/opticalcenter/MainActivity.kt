package com.example.opticalcenter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.asin

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

@Composable
fun PermissionWrapper() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        CameraScreen()
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Camera permission is required.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

// Update OpticalInfo to hold rotation data
private data class OpticalInfo(
    val screenCx: Float, val screenCy: Float,
    val opticalCx: Float, val opticalCy: Float,
    val deltaX: Float, val deltaY: Float,
    // Rotation data (in degrees)
    val pitch: Float, val roll: Float, val yaw: Float
)

@Composable
private fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    // UI States
    var opticalInfo by remember { mutableStateOf<OpticalInfo?>(null) }
    var statusMessage by remember { mutableStateOf("Initializing camera systems...") }
    var isError by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(Unit) {
        runCatching {
            statusMessage = "Discovering optical center..."
            val result = discoverOpticalCenter(context)

            statusMessage = "Binding CameraX preview..."
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider.unbindAll()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)

            statusMessage = "Waiting for layout..."
            previewView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (previewView.width > 0 && previewView.height > 0) {
                        try {
                            opticalInfo = result.computeOnPreview(previewView.width, previewView.height)
                            // Clear status message on success
                            statusMessage = ""
                            previewView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        } catch (e: Exception) {
                            isError = true
                            statusMessage = "Calculation Error: ${e.message}"
                        }
                    }
                }
            })
        }.onFailure {
            isError = true
            statusMessage = "Error: ${it.message}\n${it.stackTrace.take(3).joinToString("\n")}"
            Log.e("OpticalCenter", "Fatal Error", it)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        opticalInfo?.let { info ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Red Cross (Screen Center)
                drawLine(Color.Red, Offset(info.screenCx - 40, info.screenCy), Offset(info.screenCx + 40, info.screenCy), 5f)
                drawLine(Color.Red, Offset(info.screenCx, info.screenCy - 40), Offset(info.screenCx, info.screenCy + 40), 5f)
                // Green Cross (Optical Center)
                drawLine(Color.Green, Offset(info.opticalCx - 40, info.opticalCy), Offset(info.opticalCx + 40, info.opticalCy), 5f)
                drawLine(Color.Green, Offset(info.opticalCx, info.opticalCy - 40), Offset(info.opticalCx, info.opticalCy + 40), 5f)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha=0.6f))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Pos Delta: ${info.deltaX.toInt()} px, ${info.deltaY.toInt()} px",
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    text = "Rot Delta: P:%.5f°, R:%.5f°, Y:%.5f°".format(info.pitch, info.roll, info.yaw),
                    color = Color.Yellow,
                    fontSize = 14.sp
                )
            }
        }

        // Show Status/Error Message if not successful yet
        if (opticalInfo == null) {
            Text(
                text = statusMessage,
                color = if (isError) Color.Red else Color.Yellow,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp).background(Color.Black.copy(alpha=0.7f))
            )
        }
    }
}

// ---------------- LOGIC ----------------

private data class DiscoveryResult(
    val logicalCameraId: String, val physicalCameraId: String,
    val activeArray: Rect, val cx: Float, val cy: Float, val sensorOrientation: Int,
    val pitch: Float, val roll: Float, val yaw: Float // Added rotation fields
) {
    fun computeOnPreview(viewWidth: Int, viewHeight: Int): OpticalInfo {
        val sensorW = activeArray.width().toFloat()
        val sensorH = activeArray.height().toFloat()
        val sensorAspect = sensorW / sensorH
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        val scale = if (viewAspect > sensorAspect) viewWidth / sensorW else viewHeight / sensorH

        val (viewCx, viewCy) = if (sensorOrientation == 90 || sensorOrientation == 270) {
            val rotatedCx = if (sensorOrientation == 90) cy else sensorH - cy
            val rotatedCy = if (sensorOrientation == 90) sensorW - cx else cx
            val scaledCx = rotatedCx * scale
            val scaledCy = rotatedCy * scale
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            val drawnW = sensorH * scale
            val drawnH = sensorW * scale

            centerX + (scaledCx - drawnW / 2f) to centerY + (scaledCy - drawnH / 2f)
        } else {
            val scaledCx = cx * scale
            val scaledCy = cy * scale
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            val drawnW = sensorW * scale
            val drawnH = sensorH * scale

            centerX + (scaledCx - drawnW / 2f) to centerY + (scaledCy - drawnH / 2f)
        }

        val screenCx = viewWidth / 2f
        val screenCy = viewHeight / 2f

        return OpticalInfo(
            screenCx, screenCy, viewCx, viewCy, viewCx - screenCx, viewCy - screenCy,
            pitch, roll, yaw // Pass rotation through
        )
    }
}

private fun discoverOpticalCenter(context: Context): DiscoveryResult {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // 1. Find Logical Back Camera
    val logicalId = cameraManager.cameraIdList.firstOrNull { id ->
        val chars = cameraManager.getCameraCharacteristics(id)
        chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: error("No back camera found on device")

    val logicalChars = cameraManager.getCameraCharacteristics(logicalId)

    // 2. Get Physical IDs (Safe API Check)
    val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        logicalChars.physicalCameraIds ?: emptySet()
    } else emptySet()

    // 3. Determine which camera has intrinsics
    val (chosenId, chosenChars) = if (logicalChars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null) {
        logicalId to logicalChars
    } else {
        val candidates = physicalIds.mapNotNull { pid ->
            val c = cameraManager.getCameraCharacteristics(pid)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                pid to c
            } else null
        }
        val best = candidates.firstOrNull()
        if (best != null) best else logicalId to logicalChars
    }

    // 4. Extract Calibration Data
    val activeArray = chosenChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?: error("Camera $chosenId has no Active Array Size")

    val orientation = chosenChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val intrinsics = chosenChars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)

    val cx: Float
    val cy: Float

    if (intrinsics != null) {
        cx = intrinsics[2]
        cy = intrinsics[3] // Correct index for cy
    } else {
        Log.w("OpticalCenter", "No intrinsics found for ID $chosenId. Using center fallback.")
        cx = activeArray.width() / 2f
        cy = activeArray.height() / 2f
    }

    // 5. EXTRACT ROTATION (POSE)
    var pitch = 0f
    var roll = 0f
    var yaw = 0f

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val poseRotation = chosenChars.get(CameraCharacteristics.LENS_POSE_ROTATION)
        if (poseRotation != null && poseRotation.size == 4) {
            // Android gives Quaternion: [x, y, z, w]
            val x = poseRotation[0]
            val y = poseRotation[1]
            val z = poseRotation[2]
            val w = poseRotation[3]

            // Convert Quaternion to Euler Angles (in degrees)
            // Roll (x-axis rotation)
            val sinr_cosp = 2 * (w * x + y * z)
            val cosr_cosp = 1 - 2 * (x * x + y * y)
            roll = Math.toDegrees(atan2(sinr_cosp.toDouble(), cosr_cosp.toDouble())).toFloat()

            // Pitch (y-axis rotation)
            val sinp = 2 * (w * y - z * x)
            pitch = if (kotlin.math.abs(sinp) >= 1)
                Math.toDegrees(kotlin.math.sign(sinp) * Math.PI / 2).toFloat()
            else
                Math.toDegrees(asin(sinp.toDouble())).toFloat()

            // Yaw (z-axis rotation)
            val siny_cosp = 2 * (w * z + x * y)
            val cosy_cosp = 1 - 2 * (y * y + z * z)
            yaw = Math.toDegrees(atan2(siny_cosp.toDouble(), cosy_cosp.toDouble())).toFloat()
        }
    }

    return DiscoveryResult(logicalId, chosenId, activeArray, cx, cy, orientation, pitch, roll, yaw)
}
