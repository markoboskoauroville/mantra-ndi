package com.mantraproductions.ndi

/**
 * Many keys, tried in order, the dead ones set aside.
 *
 * Written to `modules/keyring.md`, whose rules were paid for elsewhere in this
 * account and are repeated here only where the code has to embody them:
 *
 *  - **Never test a key speculatively.** Use the first one not known dead and
 *    let a real request find out. A dead key should cost one wasted call in
 *    its entire life, the call that discovered it, and a healthy ring should
 *    cost nothing.
 *  - **One key per job, never one key per call.** A job takes a key at the
 *    start and keeps it to the end. Rotating mid-job hands a provider work
 *    that belongs to a different account.
 *  - **Five states, not four.** No credit is neither working nor refused:
 *    calling it working sends the ring at a wall, calling it refused has
 *    somebody delete a live account they only needed to top up.
 *  - **A retry hint wins over money words**, because out of credit and merely
 *    throttled are the same status and often the same word.
 *
 * Nothing here imports Android, so all of it is testable on a desk.
 */
object KeyRing {

    enum class State {
        /** It did the work. */
        WORKING,

        /** Throttled this minute. Wait, never delete. */
        BUSY,

        /** Real key, live account, no money. Top up or delete deliberately. */
        NO_CREDIT,

        /** Wrong, revoked, or the wrong provider for this shape. */
        REFUSED,

        /** The answer says nothing about the key. No network, a gateway page. */
        UNKNOWN
    }

    data class Entry(val key: String, val state: State = State.UNKNOWN) {
        /** Only the last four, ever, for anything a person or a log will see. */
        val masked: String get() = if (key.length <= 8) "…" else "…${key.takeLast(4)}"
    }

    /**
     * Pulls keys out of whatever the operator hands over.
     *
     * A file of keys is often not a file of keys. It is a note with a date at
     * the top, an account name beside each one, and a blank line where
     * something was deleted. So the shape is searched for rather than the file
     * being parsed, and order is kept, because the ring walks in order.
     */
    fun parse(text: String): List<String> {
        val found = Regex("gsk_[A-Za-z0-9]{20,}").findAll(text).map { it.value }.toList()
        return found.distinct()
    }

    /**
     * The next key to use for a job: the first that is not known dead.
     *
     * Busy and unknown are both worth trying. Busy means it was throttled at
     * some point, which says nothing about this minute, and unknown means
     * nobody has learned anything about it yet.
     */
    fun nextUsable(entries: List<Entry>, after: String? = null): Entry? {
        val usable = entries.filter { it.state != State.REFUSED && it.state != State.NO_CREDIT }
        if (after == null) return usable.firstOrNull()
        // Walk forward only. A ring never walks back into a key it has buried,
        // and never back to the one that just failed.
        val index = usable.indexOfFirst { it.key == after }
        return if (index >= 0) usable.getOrNull(index + 1) else usable.firstOrNull()
    }

    /**
     * What an answer means about the key that produced it.
     *
     * The order is the whole of it. A retry hint is checked before any money
     * word, because a provider answers a spent account and an impatient one
     * with the same status and often the same sentence; match on the money
     * words first and somebody is told to delete a live key for pressing a
     * button twice in a second.
     *
     * Status codes are checked last, since providers disagree about them: the
     * same fact is a 400 at one, a 402 at another and a 429 at a third.
     */
    fun classify(httpCode: Int, body: String): State {
        val text = body.lowercase()

        if (httpCode in 200..299) return State.WORKING

        // 1. Did it tell us how long to wait? Then it is a throttle, whatever
        //    else the sentence says.
        val retryHints = listOf(
            "retrydelay", "retry-after", "retryinfo", "quotafailure",
            "per minute", "per-minute", "try again in", "rate limit", "rate_limit"
        )
        if (retryHints.any { it in text }) return State.BUSY

        // 2. Money words, and the unambiguous ones only. Quota and exhausted
        //    are shared with throttles and cannot decide this on their own.
        val strongMoney = listOf(
            "credit", "balance", "depleted", "insufficient", "billing",
            "payment", "prepayment", "zero_credits", "e0300"
        )
        if (strongMoney.any { it in text }) return State.NO_CREDIT

        // 3. Words that mean the key itself is wrong.
        val refusal = listOf(
            "invalid api key", "invalid_api_key", "incorrect api key",
            "unauthorized", "authentication", "revoked", "disabled", "no such key"
        )
        if (refusal.any { it in text }) return State.REFUSED

        // 4. Only now the code, and only where it is unambiguous.
        return when (httpCode) {
            401, 403 -> State.REFUSED
            429 -> State.BUSY
            in 500..599 -> State.UNKNOWN
            else -> State.UNKNOWN
        }
    }

    /**
     * Applies an outcome without losing anything.
     *
     * A key that answers once is not promoted out of a state it earned: an
     * account that reported no credit and then happens to return a cached 200
     * has not been topped up. Only a real success clears it, and success is
     * only ever reported by the call that did the work.
     */
    fun record(entries: List<Entry>, key: String, state: State): List<Entry> =
        entries.map { if (it.key == key) it.copy(state = state) else it }

    /** True when nothing on the ring can be tried again without intervention. */
    fun isExhausted(entries: List<Entry>): Boolean =
        entries.isNotEmpty() && entries.all {
            it.state == State.REFUSED || it.state == State.NO_CREDIT
        }

    /** One line for the settings screen, naming no key. */
    fun summarise(entries: List<Entry>): String {
        if (entries.isEmpty()) return "No keys loaded"
        val working = entries.count { it.state == State.WORKING }
        val dead = entries.count { it.state == State.REFUSED }
        val broke = entries.count { it.state == State.NO_CREDIT }
        val untried = entries.count { it.state == State.UNKNOWN || it.state == State.BUSY }
        return buildString {
            append(entries.size).append(" keys")
            if (working > 0) append(", ").append(working).append(" working")
            if (untried > 0) append(", ").append(untried).append(" untried")
            if (broke > 0) append(", ").append(broke).append(" out of credit")
            if (dead > 0) append(", ").append(dead).append(" refused")
        }
    }
}
