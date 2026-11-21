package top.cenmin.tailcontrol.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import top.cenmin.tailcontrol.core.model.TailscaleSettings
import javax.inject.Inject
import javax.inject.Singleton

private object Keys {
    val ACCEPT_ROUTES = booleanPreferencesKey("acceptRoutes")
    val ACCEPT_DNS = booleanPreferencesKey("acceptDns")
    val EXIT_NODE = stringPreferencesKey("exitNode")
    val ADVERTISE_EXIT_NODE = booleanPreferencesKey("advertiseExitNode")
    val ADVERTISE_ROUTES = stringPreferencesKey("advertiseRoutes")
    val CUSTOM_NAME = stringPreferencesKey("customName")
    val CUSTOM_PARAMS = stringPreferencesKey("customParams")
    val SSH_SERVER_ENABLED = booleanPreferencesKey("ssh_server_enabled")

    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val ALT_REPO_OPTIMIZATION = booleanPreferencesKey("alt_repo_optimization")
    val HEALTH_BANNER_DISABLED = booleanPreferencesKey("health_banner_disabled")
    val NAV_HIDDEN_ITEMS = stringPreferencesKey("nav_hidden_items")

    val LAST_UPDATE_CHECK_DATE = stringPreferencesKey("last_update_check_date")
}

private val Context.tailDataStore by preferencesDataStore(
    name = "tailcontrol",
    produceMigrations = { ctx ->
        listOf(
            SharedPreferencesMigration(
                context = ctx,
                sharedPreferencesName = "tailscale_prefs",
                keysToMigrate = setOf(
                    "acceptRoutes", "acceptDns", "exitNode",
                    "advertiseExitNode", "advertiseRoutes",
                    "customName", "customParams",
                ),
            ),
        )
    }
)

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store: DataStore<Preferences> = context.tailDataStore

    val tailscaleSettings: Flow<TailscaleSettings> = store.data.map { p ->
        TailscaleSettings(
            acceptRoutes = p[Keys.ACCEPT_ROUTES] ?: true,
            acceptDns = p[Keys.ACCEPT_DNS] ?: false,
            exitNode = p[Keys.EXIT_NODE].orEmpty(),
            advertiseExitNode = p[Keys.ADVERTISE_EXIT_NODE] ?: false,
            advertiseRoutes = p[Keys.ADVERTISE_ROUTES].orEmpty(),
            customName = p[Keys.CUSTOM_NAME].orEmpty(),
            customParams = p[Keys.CUSTOM_PARAMS].orEmpty(),
        )
    }

    val dynamicColorEnabled: Flow<Boolean> = store.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    val altRepoOptimizationEnabled: Flow<Boolean> = store.data.map { it[Keys.ALT_REPO_OPTIMIZATION] ?: false }

    val healthBannerDisabled: Flow<Boolean> = store.data.map { it[Keys.HEALTH_BANNER_DISABLED] ?: false }

    val sshServerEnabled: Flow<Boolean> = store.data.map { it[Keys.SSH_SERVER_ENABLED] ?: false }

    suspend fun setSshServerEnabled(enabled: Boolean) {
        store.edit { it[Keys.SSH_SERVER_ENABLED] = enabled }
    }

    val navHiddenItems: Flow<Set<String>> = store.data.map { p ->
        p[Keys.NAV_HIDDEN_ITEMS]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    suspend fun setNavHiddenItems(hidden: Set<String>) {
        store.edit { it[Keys.NAV_HIDDEN_ITEMS] = hidden.joinToString(",") }
    }

    suspend fun saveTailscaleSettings(settings: TailscaleSettings) {
        store.edit { p ->
            p[Keys.ACCEPT_ROUTES] = settings.acceptRoutes
            p[Keys.ACCEPT_DNS] = settings.acceptDns
            p[Keys.EXIT_NODE] = settings.exitNode
            p[Keys.ADVERTISE_EXIT_NODE] = settings.advertiseExitNode
            p[Keys.ADVERTISE_ROUTES] = settings.advertiseRoutes
            p[Keys.CUSTOM_NAME] = settings.customName
            p[Keys.CUSTOM_PARAMS] = settings.customParams
        }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        store.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAltRepoOptimizationEnabled(enabled: Boolean) {
        store.edit { it[Keys.ALT_REPO_OPTIMIZATION] = enabled }
    }

    suspend fun setHealthBannerDisabled(disabled: Boolean) {
        store.edit { it[Keys.HEALTH_BANNER_DISABLED] = disabled }
    }

    suspend fun getLastUpdateCheckDate(): String? {
        return store.data.map { preferences ->
            preferences[Keys.LAST_UPDATE_CHECK_DATE]
        }.first()
    }

    suspend fun setLastUpdateCheckDate(date: String) {
        store.edit { preferences ->
            preferences[Keys.LAST_UPDATE_CHECK_DATE] = date
        }
    }
}
