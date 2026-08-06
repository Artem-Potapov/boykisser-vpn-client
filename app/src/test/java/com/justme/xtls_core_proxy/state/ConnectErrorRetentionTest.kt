package com.justme.xtls_core_proxy.state

import com.justme.xtls_core_proxy.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the rule that decides whether a transition to
 * `VpnConnectionState.CONNECTING` wipes the message in `VpnViewModel.error`.
 *
 * The `error` banner has no timeout, and its only other clear path is a dismiss button the user has
 * to be present to press (`MainActivity` → `clearError()` → `clearVpnError`), so that transition is
 * what clears it in every unattended case. That made it wipe the one class of message it must not:
 * a **refusal**, which explains why the request the user just made did nothing. The winning
 * request's own `CONNECTING` landed milliseconds later and erased the explanation.
 */
class ConnectErrorRetentionTest {

    @Test
    fun anOrdinaryErrorIsClearedByTheNextAttempt() {
        // The reason the auto-clear exists, unchanged: a previous session's failure is stale the
        // moment the user (or the tile) tries again.
        val retention = ConnectErrorRetention()
        retention.onErrorShown(R.string.vpn_start_failed_error)
        assertTrue(retention.onConnectingTransition())
    }

    @Test
    fun aRefusalSurvivesTheWinningRequestsConnectingTransition() {
        // The defect. A contended Reconnect is refused, the winner then announces CONNECTING, and
        // the user is left with no trace of having been refused.
        val retention = ConnectErrorRetention()
        retention.onErrorShown(R.string.connect_request_superseded)
        assertFalse(retention.onConnectingTransition())
    }

    @Test
    fun aRefusalIsClearedByTheAttemptAfterTheWinner() {
        // The reprieve is exactly one transition wide. A refusal must not outlive the attempt it
        // was contemporaneous with, or it becomes the stale banner this rule exists to prevent.
        val retention = ConnectErrorRetention()
        retention.onErrorShown(R.string.connect_request_superseded)
        assertFalse("the winner's own transition", retention.onConnectingTransition())
        assertTrue("a genuinely new attempt", retention.onConnectingTransition())
    }

    @Test
    fun aFreshRefusalReArmsTheReprieve() {
        // Re-arming is per message, not once per ViewModel: the contention this reports is a
        // repeatable user action, and the second refusal deserves the same reprieve as the first.
        val retention = ConnectErrorRetention()
        retention.onErrorShown(R.string.connect_request_superseded)
        assertFalse(retention.onConnectingTransition())
        retention.onErrorShown(R.string.connect_request_superseded)
        assertFalse(retention.onConnectingTransition())
    }

    @Test
    fun aLaterOrdinaryErrorRevokesTheReprieve() {
        // The reprieve describes the message CURRENTLY on screen. Once a real failure has replaced
        // the refusal, protecting it would keep a stale message the user can no longer act on.
        val retention = ConnectErrorRetention()
        retention.onErrorShown(R.string.connect_request_superseded)
        retention.onErrorShown(R.string.vpn_reconnect_timeout_error)
        assertTrue(retention.onConnectingTransition())
    }

    @Test
    fun clearingTheMessageRevokesTheReprieve() {
        // Exercises the REAL clearError wiring ([clearVpnError]), not onErrorCleared alone.
        // MUTATION-VERIFIED: dropping retention.onErrorCleared() from clearVpnError leaves the
        // refusal reprieve armed, so onConnectingTransition() returns false and this fails.
        val retention = ConnectErrorRetention()
        var banner: String? = "refusal on screen"
        retention.onErrorShown(R.string.connect_request_superseded)
        clearVpnError(clearBanner = { banner = null }, retention = retention)
        assertNull("clearError must empty the banner", banner)
        assertTrue(
            "clearError must revoke the reprieve, or the next CONNECTING keeps a ghost refusal",
            retention.onConnectingTransition(),
        )
    }

    @Test
    fun withNoMessageShownTheTransitionStillClears() {
        // The common case, and the one the VM runs on every ordinary connect: nothing to protect,
        // so the answer must be the same unconditional clear as before this rule existed.
        val retention = ConnectErrorRetention()
        assertTrue(retention.onConnectingTransition())
        assertTrue("and stays that way across repeats", retention.onConnectingTransition())
    }

    @Test
    fun everyRefusalMessageIsProtected() {
        // The refusal set is the whole of the rule's data. A maintainer adding a second "your
        // request was refused" string must add it here, and this fails if the set and the
        // behaviour ever disagree.
        assertTrue(
            "connect_request_superseded is the refusal this rule was written for",
            R.string.connect_request_superseded in ConnectErrorRetention.REFUSAL_MESSAGES,
        )
        for (resId in ConnectErrorRetention.REFUSAL_MESSAGES) {
            val retention = ConnectErrorRetention()
            retention.onErrorShown(resId)
            assertFalse("$resId must survive the winner's CONNECTING", retention.onConnectingTransition())
        }
    }
}
