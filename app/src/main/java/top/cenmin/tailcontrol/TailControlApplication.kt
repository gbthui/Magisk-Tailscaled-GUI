package top.cenmin.tailcontrol

import android.app.Application
import com.topjohnwu.superuser.Shell
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import top.cenmin.tailcontrol.core.log.AppLogTree
import top.cenmin.tailcontrol.core.shell.RootShell

@HiltAndroidApp
class TailControlApplication : Application() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun appLogTree(): AppLogTree
    }

    override fun onCreate() {
        super.onCreate()
        // Configure the builder before any entry point can request a shell.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(RootShell.builder())

        val tree = EntryPointAccessors
            .fromApplication(this, AppEntryPoint::class.java)
            .appLogTree()
        Timber.plant(tree)
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Timber.tag("uncaught").e(error, "thread=%s", thread.name) }
            previous?.uncaughtException(thread, error)
        }
    }
}
