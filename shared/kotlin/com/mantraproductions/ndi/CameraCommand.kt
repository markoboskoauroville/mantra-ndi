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
    val requestState: Boolean = false,
    /**
     * v117, REMOTE CONTROL: a key of the camera's rails pressed from the monitor, by the name its own key shows
     * ("LOG", "M", "AF", "L", "L2", "CTRL", "FULL", "MARK0".."MARK2", "REC", "PEAK"…). The camera does exactly what its
     * own key does, so the remote can do everything the camera can and nothing it cannot.
     */
    val key: String? = null,
    /** v117: a tap on the picture, in the STREAM's coordinates (fractions of the NDI frame); the camera turns it. */
    val tapX: Float? = null,
    val tapY: Float? = null
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
        key?.let { sb.append(" key=\"${esc(it)}\"") }
        tapX?.let { sb.append(" tap_x=\"$it\"") }
        tapY?.let { sb.append(" tap_y=\"$it\"") }
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
                requestState = attr(xml, "request_state") == "1",
                key = attr(xml, "key")?.let { unesc(it) },
                tapX = attr(xml, "tap_x")?.toFloatOrNull(),
                tapY = attr(xml, "tap_y")?.toFloatOrNull()
            )
        }

        /** XML attribute escaping, so a status line with quotes or ampersands cannot break the frame. */
        fun esc(t: String) = t.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        fun unesc(t: String) = t.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

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
    val cameraName: String = "",
    /** v117: the camera's status line, as it shows it. */
    val status: String = "",
    /**
     * v117: the rail keys, in order: "NAME~LABEL~SUB~STATE~TINT" joined by "|". NAME is what a command presses; STATE
     * is OFF ON DEAD ARMED SHOWN; TINT an ARGB number or empty. The monitor draws them the same way.
     */
    val keys: String = "",
    /** v117: the three marks on the picture: "kind,left,top,right,bottom,argb,shown" by "|", in STREAM coordinates. */
    val marks: String = "",
    /** v117: which mark a tap and a pinch serve: 0 square, 1 circle, 2 triangle. */
    val armed: Int = 0,
    /** v117: the quarter turns from the stream to the camera's own view, so the monitor can stand it upright. */
    val turns: Int = 0
) {
    fun toXml(): String = "<$ROOT iso_min=\"$isoMin\" iso_max=\"$isoMax\"" +
            " shutter_min=\"$shutterMinNs\" shutter_max=\"$shutterMaxNs\"" +
            " iso=\"${iso ?: -1}\" shutter_ns=\"${shutterNs ?: -1}\"" +
            " recording=\"${if (recording) 1 else 0}\"" +
            " manual=\"${if (manualSupported) 1 else 0}\"" +
            " wb=\"${if (whiteBalanceSupported) 1 else 0}\"" +
            " wb_kelvin=\"${whiteBalanceKelvin ?: -1}\"" +
            " name=\"${CameraCommand.esc(cameraName)}\"" +
            " status=\"${CameraCommand.esc(status)}\" keys=\"${CameraCommand.esc(keys)}\"" +
            " marks=\"$marks\" armed=\"$armed\" turns=\"$turns\"/>"

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
                cameraName = a("name")?.let { CameraCommand.unesc(it) } ?: "",
                status = a("status")?.let { CameraCommand.unesc(it) } ?: "",
                keys = a("keys")?.let { CameraCommand.unesc(it) } ?: "",
                marks = a("marks") ?: "",
                armed = a("armed")?.toIntOrNull() ?: 0,
                turns = a("turns")?.toIntOrNull() ?: 0
            )
        }
    }
}
