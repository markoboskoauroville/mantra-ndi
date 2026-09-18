package com.mantraproductions.ndi

/**
 * The remote control vocabulary, carried as NDI metadata between the Monitor
 * phone and the Camera phone.
 *
 * NDI metadata is defined as a UTF-8 XML string, so this stays valid XML, but
 * it's deliberately flat: one element, attributes only, so it can be written
 * and read without pulling in a parser. Every field is optional, and a command
 * carries only what changed.
 *
 *   <mantra_cam iso="800" shutter_ns="20000000" ae="manual" record="start"/>
 */
data class CameraCommand(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    /** "auto" or "manual" */
    val exposureMode: String? = null,
    val whiteBalanceLock: Boolean? = null,
    val stabilization: Boolean? = null,
    val zoom: Float? = null,
    /** Manual colour temperature in Kelvin; use whiteBalanceAuto to go back. */
    val whiteBalanceKelvin: Int? = null,
    val whiteBalanceAuto: Boolean? = null,
    /** Focus as a fraction of lens travel, 0 at infinity. */
    val focus: Float? = null,
    /** Focus on a point, as fractions of the frame. Both or neither. */
    val focusX: Float? = null,
    val focusY: Float? = null,
    val focusAuto: Boolean? = null,
    /** A log curve by name, so the far camera records flat too. */
    val logCurve: String? = null,
    /** "start" or "stop" */
    val record: String? = null,
    /** Ask the camera to report its current state back. */
    val requestState: Boolean = false
) {
    fun toXml(): String {
        val sb = StringBuilder("<$ROOT")
        iso?.let { sb.append(" iso=\"$it\"") }
        shutterNs?.let { sb.append(" shutter_ns=\"$it\"") }
        exposureMode?.let { sb.append(" ae=\"$it\"") }
        whiteBalanceLock?.let { sb.append(" wb_lock=\"${if (it) 1 else 0}\"") }
        stabilization?.let { sb.append(" stab=\"${if (it) 1 else 0}\"") }
        zoom?.let { sb.append(" zoom=\"$it\"") }
        whiteBalanceKelvin?.let { sb.append(" wb_kelvin=\"$it\"") }
        whiteBalanceAuto?.let { sb.append(" wb_auto=\"${if (it) 1 else 0}\"") }
        focus?.let { sb.append(" focus=\"$it\"") }
        focusX?.let { sb.append(" focus_x=\"$it\"") }
        focusY?.let { sb.append(" focus_y=\"$it\"") }
        focusAuto?.let { sb.append(" focus_auto=\"${if (it) 1 else 0}\"") }
        logCurve?.let { sb.append(" log=\"$it\"") }
        record?.let { sb.append(" record=\"$it\"") }
        if (requestState) sb.append(" request_state=\"1\"")
        sb.append("/>")
        return sb.toString()
    }

    companion object {
        const val ROOT = "mantra_cam"

        fun parse(xml: String): CameraCommand? {
            if (!xml.contains(ROOT)) return null
            return CameraCommand(
                iso = attr(xml, "iso")?.toIntOrNull(),
                shutterNs = attr(xml, "shutter_ns")?.toLongOrNull(),
                exposureMode = attr(xml, "ae"),
                whiteBalanceLock = attr(xml, "wb_lock")?.let { it == "1" },
                stabilization = attr(xml, "stab")?.let { it == "1" },
                zoom = attr(xml, "zoom")?.toFloatOrNull(),
                whiteBalanceKelvin = attr(xml, "wb_kelvin")?.toIntOrNull(),
                whiteBalanceAuto = attr(xml, "wb_auto")?.let { it == "1" },
                focus = attr(xml, "focus")?.toFloatOrNull(),
                focusX = attr(xml, "focus_x")?.toFloatOrNull(),
                focusY = attr(xml, "focus_y")?.toFloatOrNull(),
                focusAuto = attr(xml, "focus_auto")?.let { it == "1" },
                logCurve = attr(xml, "log"),
                record = attr(xml, "record"),
                requestState = attr(xml, "request_state") == "1"
            )
        }

        private fun attr(xml: String, name: String): String? {
            val marker = "$name=\""
            val start = xml.indexOf(marker)
            if (start < 0) return null
            val from = start + marker.length
            val end = xml.indexOf('"', from)
            if (end < 0) return null
            return xml.substring(from, end)
        }
    }
}

/**
 * What the camera reports back so the operator sees real values rather than
 * whatever they last dragged a slider to.
 *
 *   <mantra_cam_state iso_min="50" iso_max="3200" iso="800" recording="1"/>
 */
data class CameraState(
    val isoMin: Int = 0,
    val isoMax: Int = 0,
    val shutterMinNs: Long = 0,
    val shutterMaxNs: Long = 0,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val recording: Boolean = false,
    val manualSupported: Boolean = false,
    val whiteBalanceSupported: Boolean = false,
    val whiteBalanceKelvin: Int? = null,
    /** What this camera calls itself, so the operator knows which phone replied. */
    val cameraName: String = ""
) {
    fun toXml(): String = "<$ROOT iso_min=\"$isoMin\" iso_max=\"$isoMax\"" +
            " shutter_min=\"$shutterMinNs\" shutter_max=\"$shutterMaxNs\"" +
            " iso=\"${iso ?: -1}\" shutter_ns=\"${shutterNs ?: -1}\"" +
            " recording=\"${if (recording) 1 else 0}\"" +
            " manual=\"${if (manualSupported) 1 else 0}\"" +
            " wb=\"${if (whiteBalanceSupported) 1 else 0}\"" +
            " wb_kelvin=\"${whiteBalanceKelvin ?: -1}\"" +
            " name=\"$cameraName\"/>"

    companion object {
        const val ROOT = "mantra_cam_state"

        fun parse(xml: String): CameraState? {
            if (!xml.contains(ROOT)) return null
            fun a(n: String) = CameraCommand.run {
                val marker = "$n=\""
                val start = xml.indexOf(marker)
                if (start < 0) null else {
                    val from = start + marker.length
                    val end = xml.indexOf('"', from)
                    if (end < 0) null else xml.substring(from, end)
                }
            }
            return CameraState(
                isoMin = a("iso_min")?.toIntOrNull() ?: 0,
                isoMax = a("iso_max")?.toIntOrNull() ?: 0,
                shutterMinNs = a("shutter_min")?.toLongOrNull() ?: 0,
                shutterMaxNs = a("shutter_max")?.toLongOrNull() ?: 0,
                iso = a("iso")?.toIntOrNull()?.takeIf { it >= 0 },
                shutterNs = a("shutter_ns")?.toLongOrNull()?.takeIf { it >= 0 },
                recording = a("recording") == "1",
                manualSupported = a("manual") == "1",
                whiteBalanceSupported = a("wb") == "1",
                whiteBalanceKelvin = a("wb_kelvin")?.toIntOrNull()?.takeIf { it >= 0 },
                cameraName = a("name") ?: ""
            )
        }
    }
}
