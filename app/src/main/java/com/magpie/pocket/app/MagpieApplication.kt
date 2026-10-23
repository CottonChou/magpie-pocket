package com.magpie.pocket.app

import android.app.Application
import com.magpie.pocket.core.data.DemoRepository
import com.magpie.pocket.core.network.MagpieApiClient
import com.magpie.pocket.core.security.CredentialStore
import com.magpie.pocket.core.security.PreferencesManager

class MagpieApplication : Application() {

    lateinit var credentialStore: CredentialStore
        private set

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var demoRepository: DemoRepository
        private set

    lateinit var apiClient: MagpieApiClient
        private set

    /** One process-owned runtime shared by every Activity and the foreground service. */
    val appViewModel: AppViewModel by lazy {
        AppViewModel(
            credentialStore = CredentialStoreConnectionSource(credentialStore),
            preferencesManager = PreferencesManagerDisplaySource(preferencesManager),
            demoRepo = demoRepository,
            apiClient = apiClient,
            keepaliveController = AndroidKeepaliveController(this)
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        credentialStore = CredentialStore(this)
        preferencesManager = PreferencesManager(this)
        demoRepository = DemoRepository(this)
        apiClient = MagpieApiClient()
    }

    companion object {
        lateinit var instance: MagpieApplication
            private set
    }
}
