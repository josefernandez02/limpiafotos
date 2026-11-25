package dev.catalogo

import android.app.Application
import dev.catalogo.data.Drafts
import dev.catalogo.data.Repo

class CatalogoApp : Application() {
    lateinit var repo: Repo
        private set
    lateinit var drafts: Drafts
        private set

    override fun onCreate() {
        super.onCreate()
        dev.catalogo.data.AppLog.init(this)
        repo = Repo(this)
        drafts = Drafts(repo, this).also { it.start() }
    }
}
