package dev.catalogo

import android.app.Application
import dev.catalogo.data.Repo
import dev.catalogo.sync.SyncManager

class CatalogoApp : Application() {
    lateinit var repo: Repo
        private set
    lateinit var sync: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        sync = SyncManager(this, repo)
    }
}
