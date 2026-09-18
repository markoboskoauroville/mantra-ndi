package com.mantraproductions.ndi

import java.io.File

/**
 * What the take was shot against, written where an editor will find it.
 *
 * MediaMuxer cannot write a tmcd track, so the timecode cannot go inside the
 * MP4 on Android without remuxing it afterwards. Rather than pretend, it is
 * written beside the file as a sidecar with the same name. Every NLE that
 * matters reads a sidecar, and the start timecode is the one number that makes
 * a clip line up with every other camera on the day. It is also unrecoverable
 * once the take is over, which is why it is written the moment recording
 * starts rather than when it stops.
 */
object TimecodeLog {

    private val seen = LinkedHashMap<String, String>()

    /** Kept for the diagnostic screen, so an unreadable device can be looked at. */
    @Synchronized
    fun record(deviceName: String, hex: String, parsed: String?) {
        seen[deviceName] = "$hex\n    ${parsed ?: "not understood"}"
        while (seen.size > 6) seen.remove(seen.keys.first())
    }

    @Synchronized
    fun report(): String {
        if (seen.isEmpty()) {
            return "Nothing heard yet.\n\nSwitch a Tentacle on, keep it near the\n" +
                "phone, and give it a few seconds. It broadcasts\n" +
                "continuously and nothing needs pairing."
        }
        return seen.entries.joinToString("\n\n") { "${it.key}\n    ${it.value}" }
    }

    fun writeSidecar(videoPath: String, start: Timecode, device: String) {
        try {
            val video = File(videoPath)
            val sidecar = File(video.parentFile, video.nameWithoutExtension + ".timecode.txt")
            sidecar.writeText(
                buildString {
                    append("file: ").append(video.name).append('\n')
                    append("start timecode: ").append(start).append('\n')
                    append("frame rate: ").append(start.fps).append('\n')
                    append("drop frame: ").append(start.dropFrame).append('\n')
                    append("source: ").append(device).append('\n')
                    append("written: ").append(java.util.Date()).append('\n')
                }
            )
        } catch (e: Exception) {
            // The take matters more than the note about it.
        }
    }
}
