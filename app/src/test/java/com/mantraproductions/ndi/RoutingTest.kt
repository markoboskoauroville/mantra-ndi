package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v135: the NDI wire switch and the sound source, every choice on a desk. */
class RoutingTest {
    private val wifi = Routing.Nic("wlan0", "192.168.1.23")
    private val hotspot = Routing.Nic("wlan1", "192.168.43.1")
    private val ncm = Routing.Nic("ncm0", "10.155.1.20")
    private val rndis = Routing.Nic("rndis0", "192.168.42.129")
    private val mobile = Routing.Nic("rmnet_data0", "100.64.3.4")

    @Test fun interfacesAreToldApartByName() {
        assertEquals(Routing.Kind.USB, Routing.kindOf("ncm0"))
        assertEquals(Routing.Kind.USB, Routing.kindOf("rndis0"))
        assertEquals(Routing.Kind.USB, Routing.kindOf("usb0"))
        assertEquals(Routing.Kind.WIFI, Routing.kindOf("wlan0"))
        assertEquals(Routing.Kind.WIFI, Routing.kindOf("swlan0"))
        assertEquals(Routing.Kind.WIFI, Routing.kindOf("ap0"))
        assertEquals(Routing.Kind.OTHER, Routing.kindOf("rmnet_data0"))
    }

    @Test fun usbPicksTheTetherAndNothingElse() {
        assertEquals(ncm, Routing.adapterFor(Routing.Transport.USB, listOf(wifi, mobile, ncm)))
        assertEquals(rndis, Routing.adapterFor(Routing.Transport.USB, listOf(rndis, wifi)))
        // No cable: no adapter, so no sender on the wrong wire
        assertNull(Routing.adapterFor(Routing.Transport.USB, listOf(wifi, mobile)))
    }

    @Test fun wifiPrefersTheJoinedNetworkOverTheHotspot() {
        assertEquals(wifi, Routing.adapterFor(Routing.Transport.WIFI, listOf(hotspot, ncm, wifi)))
        assertEquals(hotspot, Routing.adapterFor(Routing.Transport.WIFI, listOf(ncm, hotspot)))
        assertNull(Routing.adapterFor(Routing.Transport.WIFI, listOf(ncm, mobile)))
    }

    @Test fun theConfigAllowsExactlyOneAdapter() {
        assertEquals("{\"ndi\":{\"adapters\":{\"allowed\":[\"10.155.1.20\"]}}}", Routing.ndiConfig("10.155.1.20"))
        // Nothing but digits and dots reaches the JSON
        assertEquals("{\"ndi\":{\"adapters\":{\"allowed\":[\"1.2.3.4\"]}}}", Routing.ndiConfig("1.2.3.4\"]}"))
    }

    @Test fun theWireLabelSaysWhatToDo() {
        assertEquals("USB 10.155.1.20", Routing.wireLabel(Routing.Transport.USB, ncm))
        assertEquals("WI-FI 192.168.1.23", Routing.wireLabel(Routing.Transport.WIFI, wifi))
        assertTrue(Routing.wireLabel(Routing.Transport.USB, null).contains("tethering"))
    }

    private val phoneBottom = Routing.Input(1, Routing.TYPE_BUILTIN_MIC, "Pixel 7", listOf(1, 2))
    private val phoneBack = Routing.Input(2, Routing.TYPE_BUILTIN_MIC, "Pixel 7", listOf(1, 2))
    private val lark = Routing.Input(30, Routing.TYPE_USB_HEADSET, "LARK M2", listOf(2))
    private val iface = Routing.Input(31, Routing.TYPE_USB_DEVICE, "Scarlett 2i2 USB", listOf(2))
    private val wired = Routing.Input(40, Routing.TYPE_WIRED_HEADSET, "", listOf(1))
    private val telephony = Routing.Input(50, 18, "telephony", emptyList())
    private val bluetooth = Routing.Input(60, 7, "AirPods", listOf(1))

    @Test fun choicesListThePhoneOnceAndEveryDevice() {
        val c = Routing.choices(listOf(phoneBottom, phoneBack, telephony, lark, bluetooth, wired))
        assertEquals(listOf("PHONE MIC", "USB-C · LARK M2", "WIRED · headset"), c.map { it.label })
    }

    @Test fun autoTakesUsbFirstThenWiredThenThePhone() {
        assertEquals(lark, Routing.pick(Routing.AUDIO_AUTO, listOf(phoneBottom, wired, lark)))
        assertEquals(wired, Routing.pick(Routing.AUDIO_AUTO, listOf(phoneBottom, wired)))
        assertEquals(phoneBottom, Routing.pick(Routing.AUDIO_AUTO, listOf(phoneBottom, bluetooth)))
    }

    @Test fun aNamedDeviceIsFoundAgainAfterAReplugWithANewId() {
        val key = lark.key
        assertEquals("USB:LARK M2", key)
        val replugged = lark.copy(id = 99)
        assertEquals(99, Routing.pick(key, listOf(phoneBottom, iface, replugged))?.id)
        // Unplugged: null, so the reader falls back to the phone and says so
        assertNull(Routing.pick(key, listOf(phoneBottom, iface)))
    }

    @Test fun phoneMeansThePhoneEvenWithAUsbMicIn() {
        assertEquals(phoneBottom, Routing.pick(Routing.AUDIO_PHONE, listOf(lark, phoneBottom)))
        assertEquals(iface, Routing.pick(iface.key, listOf(lark, phoneBottom, iface)))
    }

    @Test fun stereoOnlyWhenAskedAndPossible() {
        assertEquals(2, Routing.channelsFor(true, lark))
        assertEquals(1, Routing.channelsFor(true, wired))
        assertEquals(1, Routing.channelsFor(false, lark))
        assertEquals(2, Routing.channelsFor(true, Routing.Input(1, Routing.TYPE_USB_DEVICE, "x", emptyList())))
    }

    @Test fun choiceLabelsReadBack() {
        assertEquals("AUTO", Routing.choiceLabel(Routing.AUDIO_AUTO))
        assertEquals("PHONE MIC", Routing.choiceLabel(Routing.AUDIO_PHONE))
        assertEquals("USB-C · LARK M2", Routing.choiceLabel(lark.key))
    }

    @Test fun stereoPcmLastsHalfAsLongPerByte() {
        // 48000 frames of stereo 16-bit = 192000 bytes = one second
        assertEquals(1_000_000L, Mechanism.pcmDurationUs(192_000, 48_000, 2))
        assertEquals(2_000_000L, Mechanism.pcmDurationUs(192_000, 48_000))
    }

    @Test fun telemetryNamesTheWireAndTheSound() {
        val t = Mechanism.telemetry(false, false, 0.0, true, 1, true, 12.0, 1, wire = "USB 10.155.1.20", sound = "USB-C · LARK M2")
        assertEquals("USB 10.155.1.20 · 12.0 Mb/s · 1 watching · ♪ USB-C · LARK M2", t[2].detail)
        val waiting = Mechanism.telemetry(false, false, 0.0, true, 1, false, 0.0, 0, wire = "USB: no cable — turn USB tethering on")
        assertEquals("USB: no cable — turn USB tethering on", waiting[2].detail)
    }
}
