package top.cenmin.tailcontrol.review

import org.junit.Test
import top.cenmin.tailcontrol.ui.nav.DropNavigationRequests

class DropNavigationRequestsTest {
    @Test fun coldStartRequestIsConsumedOnce() {
        val requests = DropNavigationRequests()
        requests.request()
        val id = checkNotNull(requests.pending.value)
        requests.consumed(id)
        check(requests.pending.value == null)
    }
    @Test fun warmSingleTaskIntentCanRequestDropAgain() {
        val requests = DropNavigationRequests()
        requests.request(); val old = checkNotNull(requests.pending.value)
        requests.consumed(old)
        requests.request()
        check(checkNotNull(requests.pending.value) > old)
    }
    @Test fun savedPendingNavigationSurvivesRecreation() {
        val requests = DropNavigationRequests(7)
        check(requests.pending.value == 7L)
        requests.request(); check(requests.pending.value == 8L)
    }
    @Test fun oldNavigationAcknowledgementCannotConsumeNewNotification() {
        val requests = DropNavigationRequests()
        requests.request(); val old = checkNotNull(requests.pending.value)
        requests.request(); requests.consumed(old)
        check(requests.pending.value != null && requests.pending.value != old)
    }
}
