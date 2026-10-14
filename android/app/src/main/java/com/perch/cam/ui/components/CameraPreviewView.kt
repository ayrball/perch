package com.perch.cam.ui.components

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.perch.cam.camera.CameraEngine
import com.perch.cam.ui.theme.NavyBackground

@Composable
fun CameraPreviewView(
    cameraEngine: CameraEngine?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(cameraEngine, previewView) {
        cameraEngine?.attachSurfaceProvider(previewView.surfaceProvider)

        onDispose {
            cameraEngine?.attachSurfaceProvider(null)
        }
    }

    Box(
        modifier = modifier.background(NavyBackground)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
