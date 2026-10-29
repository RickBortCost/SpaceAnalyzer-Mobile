package it.spaceanalyzer.mobile

import android.app.Application
import it.spaceanalyzer.mobile.link.DEMO_HOST
import it.spaceanalyzer.mobile.link.DemoLinkClient
import it.spaceanalyzer.mobile.link.LinkSession
import it.spaceanalyzer.mobile.link.SfaLinkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SfaApp : Application() {
    /** Application scope: the connection must survive Activity recreation. */
    val session = LinkSession(CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { profile, code ->
            if (profile.host == DEMO_HOST) DemoLinkClient()
            else SfaLinkClient(profile.host, profile.portNumber(), profile.pinnedFingerprint, code)
        })
}
