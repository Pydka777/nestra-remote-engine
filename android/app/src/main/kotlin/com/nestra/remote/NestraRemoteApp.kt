package com.nestra.remote

import android.app.Application
import com.nestra.remote.core.api.RemoteApi
import com.nestra.remote.core.auth.ParentAuthClient
import com.nestra.remote.core.http.UrlConnectionTransport
import com.nestra.remote.core.session.RemoteSession
import com.nestra.remote.security.KeystoreSecretStore

/** One RemoteSession per process: the whole credential lifecycle lives in :core (tested on the JVM and end-to-end). */
class NestraRemoteApp : Application() {
    val session: RemoteSession by lazy {
        val http = UrlConnectionTransport(userAgent = "NESTRA-Remote-Android/${BuildConfig.VERSION_NAME}")
        RemoteSession(
            parent = ParentAuthClient(http, BuildConfig.PARENT_BASE_URL),
            api = RemoteApi(http, BuildConfig.REMOTE_BASE_URL),
            store = KeystoreSecretStore(this),
        )
    }
}
