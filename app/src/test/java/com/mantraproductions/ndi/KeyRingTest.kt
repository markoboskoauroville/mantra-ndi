package com.mantraproductions.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 for the key ring.
 *
 * The classification cases come straight from the manifest's scars: out of
 * credit and merely throttled arrive as the same status and often the same
 * word, and getting that backwards means either sending the ring at a wall or
 * telling somebody to delete a live account.
 */
class KeyRingTest {

    private fun k(n: Int) = "gsk_" + "x".repeat(20) + n

    // --- finding keys in whatever the operator hands over --------------------

    @Test fun keysAreFoundInsideAnOrdinaryNote() {
        val note = """
            Groq accounts, September
            main account   ${k(1)}
            the old one    ${k(2)}   (nearly gone)

            spare: ${k(3)}
        """.trimIndent()
        assertEquals(listOf(k(1), k(2), k(3)), KeyRing.parse(note))
    }

    @Test fun orderIsKeptBecauseTheRingWalksInOrder() {
        val text = "${k(3)}\n${k(1)}\n${k(2)}"
        assertEquals(listOf(k(3), k(1), k(2)), KeyRing.parse(text))
    }

    @Test fun theSameKeyTwiceIsOneKey() {
        assertEquals(1, KeyRing.parse("${k(1)} and again ${k(1)}").size)
    }

    @Test fun somethingThatIsNotAKeyIsNotTakenForOne() {
        assertTrue(KeyRing.parse("no keys here, just gsk_ and a short gsk_abc").isEmpty())
    }

    // --- which key a job takes ------------------------------------------------

    @Test fun theFirstKeyNotKnownDeadIsTheOneUsed() {
        val ring = listOf(
            KeyRing.Entry(k(1), KeyRing.State.REFUSED),
            KeyRing.Entry(k(2), KeyRing.State.NO_CREDIT),
            KeyRing.Entry(k(3), KeyRing.State.UNKNOWN)
        )
        assertEquals(k(3), KeyRing.nextUsable(ring)?.key)
    }

    @Test fun busyAndUntriedAreBothWorthTrying() {
        val ring = listOf(
            KeyRing.Entry(k(1), KeyRing.State.BUSY),
            KeyRing.Entry(k(2), KeyRing.State.UNKNOWN)
        )
        assertEquals(k(1), KeyRing.nextUsable(ring)?.key)
    }

    @Test fun theRingWalksForwardAndNeverBack() {
        val ring = listOf(
            KeyRing.Entry(k(1)), KeyRing.Entry(k(2)), KeyRing.Entry(k(3))
        )
        assertEquals(k(2), KeyRing.nextUsable(ring, after = k(1))?.key)
        assertEquals(k(3), KeyRing.nextUsable(ring, after = k(2))?.key)
        assertNull(KeyRing.nextUsable(ring, after = k(3)))
    }

    @Test fun anEmptyRingOffersNothingRatherThanCrashing() {
        assertNull(KeyRing.nextUsable(emptyList()))
        assertFalse(KeyRing.isExhausted(emptyList()))
    }

    @Test fun aRingOfDeadKeysKnowsItIsFinished() {
        assertTrue(
            KeyRing.isExhausted(
                listOf(
                    KeyRing.Entry(k(1), KeyRing.State.REFUSED),
                    KeyRing.Entry(k(2), KeyRing.State.NO_CREDIT)
                )
            )
        )
    }

    // --- the five states, and the order they are decided in -------------------

    @Test fun successIsSuccess() {
        assertEquals(KeyRing.State.WORKING, KeyRing.classify(200, "{}"))
    }

    @Test fun aRetryHintBeatsEveryMoneyWordInTheSameSentence() {
        // The case the manifest records: both of these are 429, both say
        // quota, and one of them only wants you to wait.
        val throttle = """{"error":{"message":"Rate limit reached, quota exhausted,
            please try again in 31s","code":"rate_limit_exceeded"}}"""
        assertEquals(KeyRing.State.BUSY, KeyRing.classify(429, throttle))
    }

    @Test fun aSpentAccountIsNoCreditRatherThanRefused() {
        val spent = """{"error":{"message":"Your prepayment credits are depleted."}}"""
        assertEquals(KeyRing.State.NO_CREDIT, KeyRing.classify(429, spent))
    }

    @Test fun quotaAloneIsNotEnoughToCondemnAnAccount() {
        // Quota and exhausted are shared with throttles, so on their own they
        // must not spend a key. Without a money word or a hint, 429 is busy.
        assertEquals(
            KeyRing.State.BUSY,
            KeyRing.classify(429, """{"error":{"message":"quota exhausted"}}""")
        )
    }

    @Test fun aWrongKeyIsRefused() {
        assertEquals(
            KeyRing.State.REFUSED,
            KeyRing.classify(401, """{"error":{"message":"Invalid API Key"}}""")
        )
    }

    @Test fun aServerFaultSaysNothingAboutTheKey() {
        assertEquals(KeyRing.State.UNKNOWN, KeyRing.classify(503, "gateway timeout"))
        assertEquals(KeyRing.State.UNKNOWN, KeyRing.classify(0, ""))
    }

    @Test fun theCodeIsOnlyConsultedWhenTheWordsSaidNothing() {
        // A 403 whose body explains a routing mistake is not a dead key, and
        // the manifest is explicit that a ring treating every refusal as death
        // will eat itself. Here the body is silent, so the code decides.
        assertEquals(KeyRing.State.REFUSED, KeyRing.classify(403, ""))
        // But a money word in a 403 still wins over the code.
        assertEquals(
            KeyRing.State.NO_CREDIT,
            KeyRing.classify(403, "insufficient balance on this account")
        )
    }

    @Test fun classificationIgnoresCase() {
        assertEquals(
            KeyRing.State.NO_CREDIT,
            KeyRing.classify(400, "INSUFFICIENT CREDIT")
        )
    }

    // --- recording, and what must not be lost --------------------------------

    @Test fun recordingTouchesOnlyTheKeyItNames() {
        val ring = listOf(KeyRing.Entry(k(1)), KeyRing.Entry(k(2)))
        val after = KeyRing.record(ring, k(2), KeyRing.State.NO_CREDIT)
        assertEquals(KeyRing.State.UNKNOWN, after[0].state)
        assertEquals(KeyRing.State.NO_CREDIT, after[1].state)
    }

    @Test fun recordingAKeyThatIsNotOnTheRingChangesNothing() {
        val ring = listOf(KeyRing.Entry(k(1)))
        assertEquals(ring, KeyRing.record(ring, k(9), KeyRing.State.REFUSED))
    }

    // --- naming a key to a person -------------------------------------------

    @Test fun aKeyIsNamedByItsPositionAndBothEnds() {
        val key = "gsk_" + "abcdefghijklmnopqrstuvwxyz" + "789"
        val label = KeyRing.displayLabel(0, key)
        assertTrue(label, label.startsWith("Key 1"))
        assertTrue(label, label.contains("abc"))
        assertTrue(label, label.contains("789"))
    }

    @Test fun positionsAreCountedFromOneBecausePeopleAre() {
        assertTrue(KeyRing.displayLabel(2, k(1)).startsWith("Key 3"))
    }

    @Test fun theMiddleOfAKeyIsNeverShown() {
        val key = "gsk_" + "abcdefghijklmnopqrstuvwxyz" + "789"
        val label = KeyRing.displayLabel(0, key)
        assertFalse(label, label.contains("defghijklmn"))
    }

    @Test fun aKeyTooShortToSplitIsJustNumbered() {
        assertEquals("Key 1", KeyRing.displayLabel(0, "gsk_abc"))
    }

    // --- two ways to deal with a spent key -----------------------------------

    @Test fun deadKeysMoveDownWithoutAnythingBeingLost() {
        val ring = listOf(
            KeyRing.Entry(k(1), KeyRing.State.NO_CREDIT),
            KeyRing.Entry(k(2), KeyRing.State.WORKING),
            KeyRing.Entry(k(3), KeyRing.State.REFUSED),
            KeyRing.Entry(k(4))
        )
        val sorted = KeyRing.deadToBottom(ring)
        assertEquals(4, sorted.size)
        assertEquals(k(2), sorted[0].key)
        assertEquals(k(4), sorted[1].key)
        assertTrue(sorted.drop(2).all {
            it.state == KeyRing.State.NO_CREDIT || it.state == KeyRing.State.REFUSED
        })
    }

    @Test fun movingDeadKeysDownKeepsTheOrderOfTheLivingOnes() {
        val ring = listOf(
            KeyRing.Entry(k(1)), KeyRing.Entry(k(2), KeyRing.State.REFUSED), KeyRing.Entry(k(3))
        )
        val sorted = KeyRing.deadToBottom(ring)
        assertEquals(listOf(k(1), k(3), k(2)), sorted.map { it.key })
    }

    @Test fun deletingDeadKeysLeavesOnlyTheUsableOnes() {
        val ring = listOf(
            KeyRing.Entry(k(1), KeyRing.State.NO_CREDIT),
            KeyRing.Entry(k(2), KeyRing.State.BUSY),
            KeyRing.Entry(k(3), KeyRing.State.REFUSED)
        )
        assertEquals(listOf(k(2)), KeyRing.removeDead(ring).map { it.key })
    }

    @Test fun aThrottledKeyIsNeverTreatedAsDeadByEitherOption() {
        // Busy says nothing about this minute, so it must survive both.
        val ring = listOf(KeyRing.Entry(k(1), KeyRing.State.BUSY))
        assertEquals(1, KeyRing.removeDead(ring).size)
        assertEquals(k(1), KeyRing.deadToBottom(ring)[0].key)
    }

    // --- never showing a key --------------------------------------------------

    @Test fun onlyTheLastFourAreEverVisible() {
        // Built rather than written, so a scan for key-shaped strings in this
        // repository keeps meaning something.
        val entry = KeyRing.Entry("gsk_" + "abcdefghijklmnopqrstuvwxyz" + "1234")
        assertEquals("...1234", entry.masked)
        assertFalse(entry.masked.contains("gsk_"))
        assertFalse(entry.masked.contains("abcdefgh"))
    }

    @Test fun aShortStringIsNotPartlyRevealedByMasking() {
        assertEquals("...", KeyRing.Entry("gsk_").masked)
    }

    @Test fun theSummaryNamesNoKey() {
        val ring = listOf(
            KeyRing.Entry(k(1), KeyRing.State.WORKING),
            KeyRing.Entry(k(2), KeyRing.State.NO_CREDIT)
        )
        val summary = KeyRing.summarise(ring)
        assertFalse(summary.contains("gsk_"))
        assertTrue(summary.contains("2 keys"))
        assertTrue(summary.contains("out of credit"))
    }

    @Test fun anEmptyRingSummarisesHonestly() {
        assertEquals("No keys loaded", KeyRing.summarise(emptyList()))
    }
}
