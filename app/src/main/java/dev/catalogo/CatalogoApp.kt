package dev.catalogo

import android.app.Application
import dev.catalogo.data.AppLog
import dev.catalogo.data.CatalogRepository
import dev.catalogo.data.pc.PcSync

class CatalogoApp : Application() {
    lateinit var repo: CatalogRepository
        private set
    lateinit var pcSync: PcSync
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        repo = CatalogRepository(this)
        pcSync = PcSync(repo, this).also { it.start() }
    }
}
