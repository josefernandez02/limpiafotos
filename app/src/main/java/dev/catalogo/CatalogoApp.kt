package dev.catalogo

import android.app.Application
import dev.catalogo.data.PcSync
import dev.catalogo.data.CatalogRepository

class CatalogoApp : Application() {
    lateinit var repo: CatalogRepository
        private set
    lateinit var pcSync: PcSync
        private set

    override fun onCreate() {
        super.onCreate()
        dev.catalogo.data.AppLog.init(this)
        repo = CatalogRepository(this)
        pcSync = PcSync(repo, this).also { it.start() }
    }
}
