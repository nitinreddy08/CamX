package com.nitin.filmcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/**
 * Plain-text report of every camera Android exposes to this app and what each can do.
 *
 * Why it exists: the phone maker decides which frame rates third-party apps may use, per camera.
 * The authoritative list is CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES; if no range there reaches
 * 60, a normal recording session on that camera cannot run at 60 fps, whatever the app asks.
 * The report also probes camera IDs that aren't in the public list (some phones hide extra
 * lenses) and the physical lenses behind a "logical" multi-camera, and shows the separate
 * high-speed (slow-motion) modes. Reading characteristics doesn't open any camera.
 */
object CameraReport {

    fun build(context: Context): String {
        val cm = context.getSystemService(CameraManager::class.java) ?: return "No camera service"
        val sb = StringBuilder()
        val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        sb.appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Listed camera IDs: ${listed.joinToString()}")
        sb.appendLine()

        val seen = LinkedHashSet<String>()
        fun describe(id: String, label: String) {
            if (!seen.add(id)) return
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "back"
                CameraMetadata.LENS_FACING_FRONT -> "front"
                CameraMetadata.LENS_FACING_EXTERNAL -> "external"
                else -> "?"
            }
            val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "%.1f".format(it) }
            val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.joinToString { "[${it.lower},${it.upper}]" } ?: "none"
            val has60 = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper >= 60 } == true
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val highSpeed = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
            val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

            sb.appendLine("Camera $id ($label) - $facing, $level, focal ${focal ?: "?"} mm")
            sb.appendLine("  Normal frame rates: $fps")
            sb.appendLine("  60 fps for apps: ${if (has60) "YES" else "no"}")
            if (highSpeed) {
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val hs = runCatching {
                    map?.highSpeedVideoSizes?.joinToString { s ->
                        "${s.width}x${s.height}@" + map.getHighSpeedVideoFpsRangesFor(s).joinToString("/") { "${it.upper}" }
                    }
                }.getOrNull()
                sb.appendLine("  High-speed (slow-motion) modes: ${hs ?: "?"}")
            }
            if (logical) {
                val physical = c.physicalCameraIds
                sb.appendLine("  Multi-lens camera made of: ${physical.joinToString()}")
                sb.appendLine()
                physical.forEach { describe(it, "lens inside $id") }
            } else {
                sb.appendLine()
            }
        }

        listed.forEach { describe(it, "listed") }
        // Some phones hide extra lenses from the public list but still answer for their IDs.
        for (i in 0..15) {
            val id = i.toString()
            if (id !in seen && runCatching { cm.getCameraCharacteristics(id) }.isSuccess) describe(id, "not listed")
        }
        return sb.toString().trimEnd()
    }
}
