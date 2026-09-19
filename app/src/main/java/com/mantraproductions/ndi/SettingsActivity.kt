package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mantraproductions.ndi.databinding.ActivitySettingsBinding
import java.io.File

/**
 * Everything that is not touched while shooting.
 *
 * Laid out like the phone's own settings on purpose: grouped cards, a title
 * and its current value on every row. Nothing here needs learning, and none of
 * it sits on top of the image.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppSettings
    private lateinit var profileStore: ProfileStore
    private lateinit var identity: SourceIdentity

    /**
     * The system picker, so a LUT can come from anywhere: Downloads, Drive, a
     * USB stick, wherever the colourist sent it. Going through the picker also
     * means no storage permission is involved, because the operator granting
     * access to one file is the permission.
     */
    /**
     * A picker for the key file, because keys arrive as a note in Downloads or
     * Drive rather than as something anybody wants to retype on a phone. The
     * file is read, the keys are found inside whatever else it contains, and
     * the file itself is not kept.
     */
    private val keyFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val added = KeyRingStore(this).import(text)
            toast(
                if (added == 0) "No new keys found in that file"
                else "Added $added key" + if (added == 1) "" else "s"
            )
            refresh()
        } catch (e: Exception) {
            toast("Could not read that file")
        }
    }

    private val lutPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            val parsed = text?.let { CubeLut.parse(it) }
            if (parsed == null) {
                toast("That file is not a 3D cube LUT this app can read")
            } else {
                // Copied in, because a picked uri is not guaranteed to still
                // resolve next launch and a monitor LUT has to survive one.
                val copy = File(lutFolder(), uri.lastPathSegment?.substringAfterLast('/')
                    ?.takeIf { it.endsWith(".cube", true) } ?: "monitor.cube")
                copy.writeText(text)
                prefs.monitorLutName = "${copy.name}, ${parsed.size} cube"
                refresh()
                toast("Loaded ${parsed.title}, ${parsed.size} cube")
            }
        } catch (e: Exception) {
            toast("Could not read that file: ${e.message}")
        }
    }

    private fun lutFolder(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            ),
            MediaStoreOutput.FOLDER
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppSettings(this)
        profileStore = ProfileStore(this)
        identity = SourceIdentity(this)

        modeTabs().forEach { (tab, mode) ->
            tab.setOnClickListener {
                // System is a place to look at things, not a way of working,
                // so choosing it does not change what the camera screen does.
                if (mode != AppMode.SYSTEM) {
                    prefs.appMode = mode
                    prefs.remoteMode = mode == AppMode.REMOTE
                }
                shownMode = mode
                if (mode == AppMode.REMOTE && prefs.remoteSource == null) pickRemoteSource()
                refresh()
            }
        }

        binding.rowLens.setOnClickListener { pickLens() }
        binding.rowRecording.setOnClickListener { pickRecording() }
        binding.rowRecording.setOnLongClickListener {
            pickStreamQuality()
            true
        }
        binding.rowScreen.setOnClickListener { manageScreenAndBattery() }
        binding.rowScreen.setOnLongClickListener {
            resetToSafeState()
            true
        }
        binding.rowFocusTiming.setOnClickListener { pickFocusTiming() }
        binding.rowWaveform.setOnClickListener { pickWaveform() }
        binding.rowBandwidth.setOnClickListener { manageBandwidth() }
        binding.rowNetwork.setOnClickListener { runNetworkTest() }
        binding.rowScreen.setOnClickListener { manageScreenAndBattery() }
        binding.rowTimecode.setOnClickListener { manageTimecode() }
        binding.rowTimecodeSync.setOnClickListener { manageLtc() }
        binding.rowTimecodeBurn.setOnClickListener { pickTimecodeDisplay() }
        binding.rowProfile.setOnClickListener { pickProfile() }
        binding.rowSourceName.setOnClickListener { editSourceName() }
        binding.rowBitDepth.setOnClickListener { pickBitDepth() }
        binding.rowLogCurve.setOnClickListener { pickLogCurve() }
        binding.rowExportLut.setOnClickListener { exportLut() }
        binding.rowLoadLut.setOnClickListener { loadLut() }
        binding.rowHistogram.setOnClickListener {
            prefs.vectorscopeVisible = !prefs.vectorscopeVisible
            refresh()
        }
        binding.rowStabilisation.setOnClickListener {
            prefs.stabilisation = !prefs.stabilisation
            refresh()
        }
        binding.rowCamera.setOnClickListener { showCameraCapabilities() }
        binding.rowDetect.setOnClickListener { showFullReport() }
        binding.rowKeys.setOnClickListener { showKeys() }
        binding.rowFocusBox.setOnClickListener { pickFocusBox() }
        binding.rowAiFocus.setOnClickListener { manageKeyRing() }

        refresh()
    }

    /**
     * Which tab is being looked at. Usually the working mode, but System can
     * be opened without changing how the camera behaves.
     */
    private var shownMode: AppMode? = null

    private fun modeTabs() = listOf(
        binding.modeLocal to AppMode.LOCAL,
        binding.modeRemote to AppMode.REMOTE,
        binding.modeMonitor to AppMode.MONITOR,
        binding.modeSystem to AppMode.SYSTEM
    )

    /**
     * Every row, and the modes it means anything in.
     *
     * Written out rather than inferred, because the question is not what a row
     * touches but whether the answer changes anything in that mode. A capture
     * profile is meaningless when this phone is not the camera. A focus rack
     * is meaningless when the lens is in another building, since the rack is
     * driven here and there is no command for it. The scopes apply wherever
     * there is a picture, which is all three working modes.
     *
     * Anything about the phone itself lives in System, including testing the
     * camera: what the hardware can do is not a camera setting, it is a fact
     * about the device that happens to mention a camera.
     */
    private fun rowsFor(mode: AppMode): Map<android.view.View, Boolean> {
        val local = mode == AppMode.LOCAL
        val remote = mode == AppMode.REMOTE
        val monitor = mode == AppMode.MONITOR
        val system = mode == AppMode.SYSTEM
        val hasPicture = local || remote || monitor
        val hasLens = local || remote

        return mapOf(
            // This phone's own camera and what it announces itself as.
            binding.rowLens to local,
            binding.rowRecording to local,
            binding.rowProfile to local,
            binding.rowSourceName to local,
            binding.rowBitDepth to local,
            binding.rowStabilisation to hasLens,

            // Colour. The curve reaches a remote camera too, since it is sent
            // as a command, but exporting a LUT is about what is being shot.
            binding.rowLogCurve to hasLens,
            binding.rowExportLut to hasLens,
            // A monitor LUT corrects a picture, and every working mode has one.
            binding.rowLoadLut to hasPicture,

            // Focus. The box and the AI keys work at either end; the rack does
            // not, because it is driven here and there is no command for it.
            binding.rowFocusBox to hasLens,
            binding.rowAiFocus to hasLens,
            binding.rowFocusTiming to local,
            // Timecode is about what this phone records, so it belongs where
            // this phone is the camera.
            binding.rowTimecode to local,

            // Scopes read pixels, so anywhere there are pixels.
            binding.rowWaveform to hasPicture,
            binding.rowHistogram to hasPicture,

            // The phone, not the job.
            binding.rowCamera to system,
            binding.rowDetect to system,
            binding.rowBandwidth to system,
            binding.rowNetwork to system,
            binding.rowScreen to system,
            binding.rowTimecode to system,
            binding.rowTimecodeSync to hasPicture,
            binding.rowTimecodeBurn to hasPicture,
            binding.rowKeys to system,
            binding.rowAbout to system
        )
    }

    private fun refresh() {
        val mode = shownMode ?: prefs.appMode.also { shownMode = it }
        modeTabs().forEach { (tab, which) ->
            val selected = which == mode
            tab.setTextColor(
                android.graphics.Color.parseColor(if (selected) "#E7A44C" else "#7C8894")
            )
            tab.alpha = if (selected) 1f else 0.7f
        }
        binding.modeDetail.text = mode.detail +
            if (mode == AppMode.REMOTE) ": ${prefs.remoteSource ?: "no camera picked"}" else ""

        // Nothing that does nothing. A row that cannot change anything in this
        // mode is not dimmed or left to be pressed, it is not there.
        for ((row, wanted) in rowsFor(mode)) {
            row.visibility = if (wanted) android.view.View.VISIBLE else android.view.View.GONE
        }

        // And a card with nothing left in it is not a card.
        listOf(
            binding.groupCapture, binding.groupColour,
            binding.groupDisplay, binding.groupDevice
        ).forEach { group ->
            val anyVisible = (0 until group.childCount).any {
                group.getChildAt(it).visibility == android.view.View.VISIBLE
            }
            group.visibility = if (anyVisible) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.valueTimecodeSync.text = when (prefs.ltcRole) {
            LtcEngine.Role.MASTER -> "Master, generating at " + prefs.ltcRate.label
            LtcEngine.Role.FOLLOW -> "External, from " + (prefs.timecodeSource ?: "anything")
            LtcEngine.Role.OFF -> "Internal, this phone's own clock"
        }
        binding.valueTimecodeBurn.text = if (!prefs.showTimecode) "Hidden" else buildString {
            append(prefs.timecodeSize).append("sp ")
            append(if (prefs.timecodeAtTop) "top" else "bottom")
            append(if (prefs.timecodePlate) ", plate" else ", no plate")
        }

        binding.valueTimecode.text = buildString {
            append(
                when (prefs.ltcRole) {
                    LtcEngine.Role.MASTER -> "Master at " + prefs.ltcRate.label
                    LtcEngine.Role.FOLLOW -> "Following"
                    LtcEngine.Role.OFF -> "Internal only"
                }
            )
            if (prefs.showTimecode) {
                append(", ").append(prefs.timecodeSize).append("sp ")
                append(if (prefs.timecodeAtTop) "top" else "bottom")
            } else {
                append(", hidden")
            }
            prefs.timecodeSource?.let { append(", from ").append(it) }
        }
        binding.valueFocusTiming.text = buildString {
            append("Hold ").append(prefs.focusHoldMs / 1000.0).append("s, rack ")
            append(if (prefs.focusRampMs == 0L) "instant" else "${prefs.focusRampMs / 1000.0}s")
        }
        binding.valueWaveform.text = prefs.waveformChannels.let { set ->
            if (set.isEmpty()) "Off" else set.joinToString(", ") { it.label }
        }

        binding.valueProfile.text = profileStore.selected().let {
            "${it.name}, ${it.width}x${it.height} at ${it.fps}"
        }
        binding.valueSourceName.text = identity.name
        binding.valueBitDepth.text = when {
            !prefs.tenBitWanted -> "8-bit, proven pipeline"
            !DeviceProfile.tenBitCapable -> "10-bit asked for, this phone is 8-bit"
            else -> "10-bit HLG, camera straight into the encoder"
        }
        binding.valueLogCurve.text = prefs.logCurve.let {
            if (it == LogCurves.Curve.REC709) "None, straight Rec.709"
            else "${it.vendor} ${it.displayName}, grey at ${"%.3f".format(LogCurves.middleGrey(it))}"
        }
        binding.valueHistogram.text = if (prefs.vectorscopeVisible) {
            "Shown. Drag the marker to balance"
        } else {
            "Hidden"
        }
        binding.valueStabilisation.text = if (prefs.stabilisation) "On" else "Off"
        binding.valueCamera.text = capabilitySummary()
        binding.valueFocusBox.text = when (prefs.focusBoxSize) {
            FocusSquareView.Size.SMALL -> "Small"
            FocusSquareView.Size.MEDIUM -> "Medium"
            FocusSquareView.Size.LARGE -> "Large"
            FocusSquareView.Size.FULL -> "Full screen"
        }
        binding.valueAiFocus.text = KeyRing.summarise(KeyRingStore(this).load()) +
            ", on this phone only"
        binding.valueKeys.text = if (KeyService.isEnabled(this)) {
            "On. Volume rocker drives the camera"
        } else {
            "Off. Tap to enable, then the rocker works everywhere"
        }
        binding.valueAbout.text = "Mantra NDI v${BuildConfig.VERSION_NAME}" +
                if (BuildConfig.NDI_SDK_PRESENT) "" else ", built without the NDI SDK"
        binding.valueLoadLut.text = prefs.monitorLutName ?: "None loaded"
    }

    /**
     * Local or remote. Remote asks the network who is out there rather than
     * making the operator type a name, because the whole point of NDI is that
     * the sources announce themselves.
     */
    private fun pickRemoteSource() {
        run {
            if (!NdiFinder.available) {
                toast("This build has no NDI SDK, so there is nothing to find")
                return
            }
            toast("Looking for cameras")
            kotlin.concurrent.thread(name = "remote-scan") {
                NdiFinder.start(applicationContext)
                val found = NdiFinder.sources(timeoutMs = 3000)
                NdiFinder.stop()
                runOnUiThread {
                    if (found.isEmpty()) {
                        toast("No sources found. Same network as the camera?")
                    } else {
                        choose("Which camera", found) { pick ->
                            prefs.remoteSource = found[pick]
                            refresh()
                        }
                    }
                }
            }
        }
    }

    /**
     * Checkmarks, not a mode. A luma trace and a parade answer different
     * questions and a colourist wants whichever of them the shot needs, often
     * two at once.
     */
    /**
     * Two numbers, because they are two decisions. How long focus is left
     * alone, and how long it takes to move once it is not. Zero on the second
     * is a snap, which is the right answer for anybody who wants the phone to
     * behave like a phone.
     */
    /**
     * Tentacle devices broadcast and never connect, so this only listens and
     * several cameras can share one generator. The raw advertisement is shown
     * because the byte layout is not published: if a device is heard and not
     * understood, the bytes are the thing that fixes it.
     */
    /**
     * The two things that stop a long take: the screen sleeping, and Android
     * deciding a phone that nobody is touching has nothing to do.
     */
    /**
     * Which clock this phone is, and how it looks.
     *
     * A master stamps its timecode onto every NDI frame it sends, so anything
     * receiving that source is on its clock without decoding anything extra.
     * A follower takes whatever it is given. Neither needs hardware.
     */
    private fun manageLtc() {
        val roles = listOf(
            LtcEngine.Role.OFF to "Off, internal clock only",
            LtcEngine.Role.MASTER to "Master, be the clock",
            LtcEngine.Role.FOLLOW to "Follow, listen for a clock"
        )
        choose("Timecode role", roles.map { it.second }) { index ->
            prefs.ltcRole = roles[index].first
            if (prefs.ltcRole == LtcEngine.Role.FOLLOW) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 78
                )
            }
            refresh()
        }
    }

    /** How the clock is drawn, separately from what kind of clock it is. */
    /**
     * Which device's clock to follow.
     *
     * Needed the moment more than one device can generate. Two cameras
     * following two different masters look identical if all that is shown is a
     * running number, and they stay identical until the edit.
     */
    private fun pickTimecodeSource() {
        if (!NdiFinder.available) {
            toast("This build has no NDI SDK")
            return
        }
        val waiting = AlertDialog.Builder(this)
            .setTitle("Looking for sources")
            .setCancelable(false)
            .create()
        waiting.show()

        kotlin.concurrent.thread(name = "tc-source-scan") {
            NdiFinder.start(applicationContext)
            val found = NdiFinder.sources(timeoutMs = 3000)
            NdiFinder.stop()
            runOnUiThread {
                waiting.dismiss()
                choose("Timecode from", listOf("Anything that arrives") + found) { index ->
                    prefs.timecodeSource = if (index == 0) null else found[index - 1]
                    refresh()
                }
            }
        }
    }

    private fun pickTimecodeDisplay() {
        choose(
            "Timecode display",
            listOf(
                if (prefs.showTimecode) "Hide it" else "Show it",
                "Text size, now ${prefs.timecodeSize}",
                "Position, now " + (if (prefs.timecodeAtTop) "top" else "bottom"),
                "Background, now " + (if (prefs.timecodePlate) "on" else "off"),
                "Timecode from, now " + (prefs.timecodeSource ?: "anything"),
                "Sync line, now " + (if (prefs.timecodeShowSync) "on" else "off"),
                "Status line, now " + (if (prefs.timecodeShowStatus) "on" else "off"),
                "Format line, now " + (if (prefs.timecodeShowFormat) "on" else "off"),
                "Frame rate, now ${prefs.ltcRate.label}"
            )
        ) { index ->
            when (index) {
                0 -> { prefs.showTimecode = !prefs.showTimecode; refresh() }
                1 -> choose("Text size", listOf("12", "14", "16", "20", "24", "30", "36")) { p ->
                    prefs.timecodeSize = listOf(12, 14, 16, 20, 24, 30, 36)[p]
                    refresh()
                }
                2 -> { prefs.timecodeAtTop = !prefs.timecodeAtTop; refresh() }
                3 -> { prefs.timecodePlate = !prefs.timecodePlate; refresh() }
                4 -> pickTimecodeSource()
                5 -> { prefs.timecodeShowSync = !prefs.timecodeShowSync; refresh() }
                6 -> { prefs.timecodeShowStatus = !prefs.timecodeShowStatus; refresh() }
                7 -> { prefs.timecodeShowFormat = !prefs.timecodeShowFormat; refresh() }
                8 -> {
                    val rates = Timecode.Rate.values().toList()
                    choose("Frame rate", rates.map { it.label }) { r ->
                        prefs.ltcRate = rates[r]
                        refresh()
                    }
                }
            }
        }
    }

    /**
     * Every camera the phone really has, not just the first in the list.
     *
     * Modern phones carry a main, an ultra wide, a telephoto and often a fused
     * logical camera, and which of those sits at index zero is up to the
     * vendor. Depth and infrared sensors are left out: they appear in the same
     * list and produce nothing an operator would call a shot.
     */
    private fun pickLens() {
        val lenses = CameraCatalogue.lenses(this)
        if (lenses.isEmpty()) {
            toast("No usable cameras found")
            return
        }
        val labels = listOf("Chosen by the phone") +
            lenses.map { it.label + "   " + it.maxResolution }
        choose("Lens", labels) { index ->
            prefs.selectedCameraId = if (index == 0) null else lenses[index - 1].id
            refresh()
            toast("Reopen the camera screen to switch")
        }
    }

    /**
     * Two numbers, because those are the two that change a recording: how big
     * the picture is, and how much data is spent on it.
     */
    /**
     * The recording format, laid out the way a camera menu lays it out.
     *
     * Five decisions in the order a camera asks them: what the file is, what
     * the picture is encoded as, how big, how fast, and how much data. Each
     * list is read from this phone rather than written here, so it offers 4K60
     * only on a phone that can do 4K60 and shows 10-bit only where the encoder
     * really has it, whatever the marketing said.
     */
    /**
     * What goes on the wire, which is a different decision from what goes in
     * the file.
     *
     * A recording wants every bit it can get, because it is graded later and
     * nothing puts back what the encoder threw away. A stream wants to arrive:
     * over a busy wifi a generous bitrate does not look better, it stutters,
     * and a dropped frame is worse than a soft one. They were sharing one
     * encoder, so one number had to be wrong for one of them.
     */
    private fun pickStreamQuality() {
        val p = profileStore.selected()
        val options = listOf(
            "Same as the recording" to 0,
            "12 Mbps, weak wifi" to 12,
            "20 Mbps, ordinary wifi" to 20,
            "35 Mbps, good wifi" to 35,
            "60 Mbps, wired or 6GHz" to 60
        )
        choose("Stream quality", options.map { it.first } + listOf(
            "Half size: " + (if (prefs.streamHalfSize) "on" else "off")
        )) { index ->
            if (index < options.size) {
                prefs.streamMbps = options[index].second
            } else {
                prefs.streamHalfSize = !prefs.streamHalfSize
            }
            refresh()
            toast("Restart the stream to apply")
        }
    }

    private fun pickRecording() {
        val codec = prefs.recordCodec
        val mode = currentMode()
        choose(
            "Recording format",
            listOf(
                "File: " + prefs.recordContainer.label + "   " + prefs.recordContainer.detail,
                "Codec: " + codec.label + "   " + codec.detail,
                "Resolution: " + (mode?.label ?: "unknown"),
                "Frame rate: " + profileStore.selected().fps + "p",
                "Colour: " + prefs.recordColour.label + "   " + prefs.recordColour.detail,
                "Bitrate: " + prefs.recordMbps + " Mbps"
            )
        ) { index ->
            when (index) {
                0 -> pickFrom(
                    "File", RecordingFormats.Container.values().toList(), { it.label + "   " + it.detail }
                ) { prefs.recordContainer = it }
                1 -> pickFrom(
                    "Codec", RecordingFormats.Codec.values().toList(), { it.label + "   " + it.detail }
                ) {
                    prefs.recordCodec = it
                    profileStore.applyFormat(useHevc = it == RecordingFormats.Codec.H265)
                }
                2 -> pickResolution()
                3 -> pickFrameRate()
                4 -> pickColour()
                5 -> pickBitrate()
            }
        }
    }

    private fun <T> pickFrom(
        title: String,
        options: List<T>,
        label: (T) -> String,
        onPick: (T) -> Unit
    ) {
        if (options.isEmpty()) {
            toast("This phone offers none")
            return
        }
        choose(title, options.map(label)) { index ->
            onPick(options[index])
            refresh()
        }
    }

    private fun currentMode(): RecordingFormats.Mode? {
        val p = profileStore.selected()
        return RecordingFormats.modes(this, prefs.selectedCameraId)
            .firstOrNull { it.size.width == p.width && it.size.height == p.height }
    }

    private fun pickResolution() {
        val modes = RecordingFormats.modes(this, prefs.selectedCameraId)
        pickFrom("Resolution", modes, { it.label + "   " + it.detail }) { mode ->
            profileStore.applyFormat(width = mode.size.width, height = mode.size.height)
        }
    }

    /**
     * Only the rates this size can really hold. A 4K sensor that tops out at
     * thirty must not be offered sixty, because the request succeeds and the
     * camera quietly delivers thirty.
     */
    private fun pickFrameRate() {
        val rates = currentMode()?.rates ?: listOf(24, 25, 30)
        pickFrom("Frame rate", rates, { it.toString() + "p" }) { profileStore.applyFormat(fps = it) }
    }

    private fun pickColour() {
        val modes = RecordingFormats.colourModes(prefs.recordCodec)
        pickFrom("Colour", modes, { it.label + "   " + it.detail }) { mode ->
            prefs.recordColour = mode
            prefs.tenBitWanted = mode.tenBit
        }
    }

    private fun pickBitrate() {
        val p = profileStore.selected()
        val choices = RecordingFormats.bitrateChoices(
            android.util.Size(p.width, p.height), p.fps
        )
        pickFrom("Bitrate", choices, { it.first }) {
            prefs.recordMbps = it.second
            profileStore.applyFormat(bitRate = it.second * 1_000_000)
        }
    }

    /**
     * Back to a state that works, for when something has gone wrong on set.
     *
     * Not a factory reset: identity, keys and the timecode role are what make
     * this phone this camera, and wiping those in a hurry is worse than the
     * problem. What goes is everything that can make the app heavy or make the
     * picture wrong, so the way back from a frozen preview is one press rather
     * than a reinstall.
     */
    private fun resetToSafeState() {
        AlertDialog.Builder(this)
            .setTitle("Reset to a safe state")
            .setMessage(
                "Drops to 1080p25, 8-bit Rec.709, no overlays, no grade, and " +
                    "stops streaming.\n\nKeeps the source name, the AI keys, " +
                    "the timecode role and the lens."
            )
            .setPositiveButton("Reset") { _, _ ->
                prefs.logCurve = LogCurves.Curve.REC709
                prefs.tenBitWanted = false
                prefs.recordColour = RecordingFormats.ColourMode.REC709_8
                prefs.previewLut = false
                prefs.focusPeaking = false
                prefs.waveformChannels = emptySet()
                prefs.vectorscopeVisible = false
                prefs.stabilisation = false
                profileStore.applyFormat(
                    width = 1920, height = 1080, fps = 25,
                    bitRate = 25_000_000, useHevc = false
                )
                prefs.recordMbps = 25
                refresh()
                toast("Reset. Reopen the camera screen.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun manageScreenAndBattery() {
        val exempt = PowerPolicy.isExempt(this)
        choose(
            "Screen and battery",
            listOf(
                if (prefs.keepScreenOn) "Let the screen sleep" else "Keep the screen on",
                if (exempt) "Battery already unrestricted" else "Stop Android throttling this app"
            )
        ) { index ->
            when (index) {
                0 -> {
                    prefs.keepScreenOn = !prefs.keepScreenOn
                    refresh()
                }
                1 -> {
                    if (exempt) {
                        toast("Already unrestricted")
                    } else if (!PowerPolicy.requestExemption(this)) {
                        // Some builds suppress the dialog, so send them to the
                        // list rather than leaving a button that did nothing.
                        PowerPolicy.openBatterySettings(this)
                        toast("Find Mantra NDI in the list and allow it")
                    }
                    prefs.batteryAsked = true
                }
            }
        }
    }

    private fun manageTimecode() {
        AlertDialog.Builder(this)
            .setTitle("Timecode")
            .setMessage(
                (if (prefs.timecodeEnabled) "On.\n\n" else "Off.\n\n") +
                    "A Tentacle broadcasts continuously and nothing needs\n" +
                    "pairing. Recordings get a sidecar with the start\n" +
                    "timecode beside them.\n\nLast heard:\n\n" +
                    TimecodeLog.report()
            )
            .setNeutralButton("Role") { _, _ -> manageLtc() }
            .setPositiveButton("Display") { _, _ -> pickTimecodeDisplay() }
            .setNegativeButton(if (prefs.timecodeEnabled) "Bluetooth off" else "Bluetooth on") { _, _ ->
                prefs.timecodeEnabled = !prefs.timecodeEnabled
                refresh()
            }
            .setNeutralButton("Save report") { _, _ ->
                val target = MediaStoreOutput.writeText(
                    this, "MantraNDI_timecode.txt", TimecodeLog.report()
                )
                toast(if (target == null) "Could not write it" else "Saved to " + target.shortLocation)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickFocusTiming() {
        val options = listOf(0L, 500L, 1000L, 2000L, 3000L)
        val labels = options.map { if (it == 0L) "Instant" else "${it / 1000.0}s" }

        choose("Hold before checking", labels.drop(1)) { index ->
            prefs.focusHoldMs = options.drop(1)[index]
            choose("Rack duration", labels) { rampIndex ->
                prefs.focusRampMs = options[rampIndex]
                refresh()
            }
        }
    }

    private fun pickWaveform() {
        val all = Mechanism.WaveformChannel.values()
        val chosen = prefs.waveformChannels.toMutableSet()
        val checked = BooleanArray(all.size) { all[it] in chosen }

        AlertDialog.Builder(this)
            .setTitle("Waveform")
            .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, which, on ->
                if (on) chosen.add(all[which]) else chosen.remove(all[which])
            }
            .setPositiveButton("Done") { _, _ ->
                prefs.waveformChannels = chosen
                refresh()
            }
            .setNeutralButton("Off") { _, _ ->
                prefs.waveformChannels = emptySet()
                refresh()
            }
            .show()
    }

    /**
     * Two phones, one measurement. One listens, the other pushes.
     *
     * Every quality number in this app is a guess until this is run, and the
     * usual way of settling them is to shoot something, watch it stutter, and
     * lower a number until it stops.
     */
    private fun manageBandwidth() {
        choose(
            "Bandwidth test",
            listOf(
                if (BandwidthTest.isServing) "Stop listening" else "Listen, be the far end",
                "Measure to another phone"
            )
        ) { index ->
            if (index == 0) {
                if (BandwidthTest.isServing) {
                    BandwidthTest.stopServer()
                    toast("Stopped")
                } else {
                    BandwidthTest.startServer { message ->
                        runOnUiThread { toast(message) }
                    }
                }
            } else {
                findAndMeasure()
            }
        }
    }

    private fun findAndMeasure() {
        val waiting = AlertDialog.Builder(this)
            .setTitle("Looking for a phone that is listening")
            .setMessage("The other phone needs Listen switched on.")
            .setCancelable(false)
            .create()
        waiting.show()

        kotlin.concurrent.thread(name = "bw-find") {
            val peers = BandwidthTest.findPeers()
            runOnUiThread {
                waiting.dismiss()
                if (peers.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Nothing answered")
                        .setMessage(
                            "Either the other phone is not listening, or this " +
                                "network blocks phone to phone traffic. If the " +
                                "second, NDI will not work here either, and a " +
                                "personal hotspot from one phone will prove it."
                        )
                        .setPositiveButton("Close", null)
                        .show()
                    return@runOnUiThread
                }
                choose("Measure to", peers.map { it.label }) { index ->
                    measureTo(peers[index])
                }
            }
        }
    }

    private fun measureTo(peer: BandwidthTest.Peer) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Measuring to " + peer.model)
            .setMessage("Six seconds.")
            .setCancelable(false)
            .create()
        progress.show()

        kotlin.concurrent.thread(name = "bw-measure") {
            val result = BandwidthTest.measure(peer.address) { mbps ->
                runOnUiThread {
                    progress.setMessage(String.format("%.0f Mbps", mbps))
                }
            }
            runOnUiThread {
                progress.dismiss()
                if (result == null) {
                    toast("Could not reach " + peer.address)
                    return@runOnUiThread
                }
                showBandwidthResult(peer, result)
            }
        }
    }

    /**
     * The number, and what it means for the settings that depend on it.
     *
     * A bitrate cannot be judged against a network speed without the
     * arithmetic, so the arithmetic is done here rather than left to the
     * operator on a shoot.
     */
    private fun showBandwidthResult(peer: BandwidthTest.Peer, result: BandwidthTest.Result) {
        val report = buildString {
            append(String.format("%.0f Mbps", result.megabitsPerSecond)).append("  to ")
            append(peer.model).append("\n\n")
            append(result.verdict).append("\n\n")
            append("Safe to stream at ").append(result.safeStreamMbps).append(" Mbps.\n")
            append("That is sixty per cent of what the link managed, because a\n")
            append("measurement is the best case: nothing else was talking and\n")
            append("the test was a flat stream rather than bursty video.\n\n")
            append("WHAT EACH SETTING ASKS FOR\n")
            listOf(12, 20, 35, 60).forEach { mbps ->
                val needs = BandwidthTest.requirement(mbps)
                val fits = needs <= result.safeStreamMbps
                append(String.format("  %2d Mbps needs %.0f  %s\n", mbps, needs,
                    if (fits) "fits" else "too much"))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Bandwidth")
            .setMessage(report)
            .setPositiveButton("Use " + result.safeStreamMbps + " Mbps") { _, _ ->
                prefs.streamMbps = result.safeStreamMbps
                refresh()
                toast("Stream set to " + result.safeStreamMbps + " Mbps")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runNetworkTest() {
        val waiting = AlertDialog.Builder(this)
            .setTitle("NDI network test")
            .setMessage("Listening for six seconds.")
            .setCancelable(false)
            .create()
        waiting.show()

        kotlin.concurrent.thread(name = "ndi-network-test") {
            val report = NetworkTest.run(applicationContext)
            runOnUiThread {
                waiting.dismiss()
                val view = android.widget.ScrollView(this).apply {
                    addView(android.widget.TextView(this@SettingsActivity).apply {
                        text = report
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 10f
                        setTextColor(android.graphics.Color.parseColor("#CFD8DC"))
                        setPadding(36, 26, 36, 26)
                    })
                }
                AlertDialog.Builder(this)
                    .setTitle("NDI network test")
                    .setView(view)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Save") { _, _ ->
                        val name = "MantraNDI_network_" +
                            android.os.Build.MODEL.replace(' ', '_') + ".txt"
                        val target = MediaStoreOutput.writeText(this, name, report)
                        toast(
                            if (target == null) "Could not write it"
                            else "Saved to " + target.shortLocation
                        )
                    }
                    .show()
            }
        }
    }

    private fun pickFocusBox() {
        val sizes = FocusSquareView.Size.values()
        choose("Focus box size", listOf("Small", "Medium", "Large", "Full screen")) { index ->
            prefs.focusBoxSize = sizes[index]
            refresh()
        }
    }

    /**
     * Typed in, never built in. This app's releases are public, and a key
     * compiled into an APK can be read straight back out of it by anybody who
     * downloads one.
     */
    /**
     * The ring, not a key. Importing extends it rather than replacing it, and
     * a buried key is never resurrected by re-importing the same note.
     */
    private fun manageKeyRing() {
        val store = KeyRingStore(this)
        val entries = store.load()

        val detail = if (entries.isEmpty()) {
            "No keys yet."
        } else {
            entries.mapIndexed { index, entry ->
                KeyRing.displayLabel(index, entry.key) + "   " +
                    entry.state.name.lowercase().replace('_', ' ')
            }.joinToString("\n")
        }

        AlertDialog.Builder(this)
            .setTitle("AI focus keys")
            .setMessage(
                "Tried in order. A real request decides whether one is spent.\n\n" + detail
            )
            .setPositiveButton("Test all") { _, _ -> testAllKeys(store) }
            .setNeutralButton("Import a file") { _, _ ->
                // Any type: key files are routinely served as octet-stream and
                // filtering hides the file the operator came for.
                keyFilePicker.launch(arrayOf("*/*"))
            }
            .setNegativeButton("More") { _, _ -> keyRingHousekeeping(store) }
            .show()
    }

    /**
     * The one time a speculative test is right: the operator asked for it.
     * The ring itself still never probes, because a dead key should cost the
     * single call that discovered it and nothing more.
     */
    private fun testAllKeys(store: KeyRingStore) {
        val entries = store.load()
        if (entries.isEmpty()) {
            toast("No keys to test")
            return
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("Testing keys")
            .setMessage("Asking each account to do the smallest thing it sells.")
            .setCancelable(false)
            .create()
        progress.show()

        KeyProbe.testAll(
            entries = entries,
            onProgress = { done, total, result ->
                store.record(result.key, result.state)
                runOnUiThread {
                    progress.setMessage("$done of $total tested")
                }
            },
            onFinished = {
                runOnUiThread {
                    progress.dismiss()
                    refresh()
                    manageKeyRing()
                }
            }
        )
    }

    /**
     * Two ways to deal with a spent key, because they are different decisions.
     * Moving it down stops the ring walking through it and keeps the only copy
     * this phone has; deleting it is for a key that is genuinely revoked.
     */
    private fun keyRingHousekeeping(store: KeyRingStore) {
        choose(
            "Keys",
            listOf(
                "Move dead keys to the bottom",
                "Delete dead keys",
                "Revive all, after topping up",
                "Forget every key"
            )
        ) { index ->
            when (index) {
                0 -> {
                    store.save(KeyRing.deadToBottom(store.load()))
                    toast("Dead keys moved down")
                }
                1 -> {
                    val before = store.load().size
                    store.save(KeyRing.removeDead(store.load()))
                    toast("Removed " + (before - store.load().size))
                }
                2 -> {
                    store.revive()
                    toast("Every key back in play")
                }
                3 -> store.clear()
            }
            refresh()
        }
    }

    private fun editApiKey() {
        val input = android.widget.EditText(this).apply {
            setText(prefs.groqApiKey.orEmpty())
            hint = "Groq API key"
        }
        AlertDialog.Builder(this)
            .setTitle("AI focus key")
            .setMessage("Stored on this phone only. It is never included in a build.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.groqApiKey = input.text.toString()
                refresh()
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.groqApiKey = null
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickProfile() {
        val profiles = profileStore.load()
        choose("Capture profile", profiles.map { it.name }) { index ->
            profileStore.selectedName = profiles[index].name
            refresh()
        }
    }

    private fun pickBitDepth() {
        val tenBitPossible = DeviceProfile.tenBitCapable
        choose(
            "Bit depth",
            listOf(
                "8-bit, the proven pipeline",
                if (tenBitPossible) "10-bit HLG, direct pipeline" else "10-bit, not supported here"
            )
        ) { index ->
            if (index == 1 && !tenBitPossible) {
                toast("This phone's camera is 8-bit")
                return@choose
            }
            prefs.tenBitWanted = index == 1
            refresh()
            if (index == 1) {
                toast("Camera writes straight into the encoder. Restart the camera screen.")
            }
        }
    }

    private fun pickLogCurve() {
        val curves = LogCurves.Curve.values()
        val labels = curves.map {
            if (it == LogCurves.Curve.REC709) "None, straight Rec.709"
            else "${it.vendor} ${it.displayName}"
        }
        if (!DeviceProfile.logCapable) {
            toast("This camera will not take a custom tone curve, so log is unavailable")
            return
        }
        choose("Log curve", labels) { index ->
            prefs.logCurve = curves[index]
            refresh()
            // Applied when the camera screen comes back up, since the curve
            // lives on the capture request rather than in a file.
            toast("Applied to the image when you return to the camera")
        }
    }

    /**
     * Written out when asked for, computed from the curve's constants. Nothing
     * is stored in the app, so any curve at any size is available and none of
     * them cost space until somebody wants one.
     */
    private fun exportLut() {
        val from = prefs.logCurve
        if (from == LogCurves.Curve.REC709) {
            toast("Pick a log curve first, there is nothing to undo")
            return
        }
        choose("LUT size", listOf("17, small and fast", "33, the usual", "65, finest")) { index ->
            val size = listOf(17, 33, 65)[index]
            try {
                val name = CubeLut.fileName(from, LogCurves.Curve.REC709, size)
                val target = MediaStoreOutput.writeText(
                    this, name, CubeLut.generate(from, LogCurves.Curve.REC709, size)
                )
                if (target == null) toast("Could not write the LUT")
                else toast("Written to ${target.shortLocation}")
            } catch (e: Exception) {
                toast("Could not write the LUT: ${e.message}")
            }
        }
    }

    private fun loadLut() {
        val known = lutFolder().listFiles { f -> f.name.endsWith(".cube", true) }.orEmpty()
        val options = listOf("Browse the phone\u2026", "None") + known.map { it.name }
        choose("Monitor LUT", options) { index ->
            when (index) {
                // Any MIME, because .cube is routinely served as octet-stream
                // and filtering on it hides the file the operator came for.
                0 -> lutPicker.launch(arrayOf("*/*"))
                1 -> { prefs.monitorLutName = null; refresh() }
                else -> {
                    val file = known[index - 2]
                    val parsed = CubeLut.parse(file.readText())
                    if (parsed == null) toast("Not a 3D cube LUT this app can read")
                    else {
                        prefs.monitorLutName = "${file.name}, ${parsed.size} cube"
                        refresh()
                    }
                }
            }
        }
    }

    /**
     * The whole picture in one scrollable block, and offered as a file as
     * well, because this is the thing worth keeping beside footage when a
     * shot turns out to have been impossible on that phone.
     */
    private fun showFullReport() {
        val report = DeviceReport.build(this)
        val view = android.widget.ScrollView(this).apply {
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = report
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#CFD8DC"))
                setPadding(40, 30, 40, 30)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Camera features")
            .setView(view)
            .setPositiveButton("Close", null)
            .setNeutralButton("Save") { _, _ ->
                val name = "MantraNDI_device_${Build.MODEL.replace(' ', '_')}.txt"
                val target = MediaStoreOutput.writeText(this, name, report)
                toast(if (target == null) "Could not write it" else "Saved to ${target.shortLocation}")
            }
            .show()
    }

    /**
     * Whether the power key reaches an app at all is undocumented and differs
     * between builds, so this shows what this phone has actually delivered
     * rather than asserting either way.
     */
    private fun showKeys() {
        val message = buildString {
            append(if (KeyService.isEnabled(this@SettingsActivity)) "Service is on.\n\n"
                   else "Service is off. Enable Mantra NDI in Accessibility.\n\n")
            append("Keys this phone has delivered:\n")
            append(KeyService.seenKeyReport())
            append("\n\nVolume rocker: adjusts whatever is on screen, ")
            append("zooms when nothing is, records when held.")
        }
        AlertDialog.Builder(this)
            .setTitle("Hardware keys")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNeutralButton("Accessibility settings") { _, _ ->
                startActivity(KeyService.settingsIntent())
            }
            .show()
    }

    private fun showCameraCapabilities() {
        AlertDialog.Builder(this)
            .setTitle("This camera")
            .setMessage(capabilityDetail())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun capabilitySummary(): String = DeviceProfile.summary.ifEmpty { legacySummary() }

    private fun legacySummary(): String = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull() ?: return "No camera"
        HdrCapabilities(manager.getCameraCharacteristics(id)).summary()
    } catch (e: Exception) {
        "Unavailable"
    }

    private fun capabilityDetail(): String = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        buildString {
            for (id in manager.cameraIdList) {
                val caps = HdrCapabilities(manager.getCameraCharacteristics(id))
                append("Camera ").append(id).append('\n')
                append("  ").append(caps.summary()).append('\n')
                append("  10-bit profiles: ").append(caps.supportedProfiles.size).append('\n')
                append("  tone curve points: ").append(caps.maxCurvePoints).append("\n\n")
            }
        }
    } catch (e: Exception) {
        "Could not read the camera: ${e.message}"
    }

    private fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(
                ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
            ) { _, which -> onPick(which) }
            .show()
    }

    private fun editSourceName() {
        val input = android.widget.EditText(this).apply { setText(identity.name) }
        AlertDialog.Builder(this)
            .setTitle("NDI source name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                identity.name = input.text.toString()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
}
