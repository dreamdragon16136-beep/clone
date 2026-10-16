package com.imperialreader.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.imperialreader.data.cover.AutomaticCoverRefreshScheduler
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.data.update.LibraryUpdateScheduler
import com.imperialreader.util.app
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.imperialreader.R
import com.imperialreader.ui.browse.BrowseFragment
import com.imperialreader.ui.history.HistoryFragment
import com.imperialreader.ui.library.LibraryFragment
import com.imperialreader.ui.more.MoreFragment
import com.imperialreader.ui.updates.UpdatesFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private var refreshedThisProcess = false
        private var scannedThisProcess = false
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.nav)
        nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_library
            AutomaticCoverRefreshScheduler.onAppStart(this)
            LibraryUpdateScheduler.onAppStart(this)
        }

        if (app.prefs.localCovers && app.prefs.storageUri != null && !scannedThisProcess) {
            scannedThisProcess = true
            lifecycleScope.launch { runCatching { LocalScanner(applicationContext).scan() } }
        }
        val updateInterval = TimeUnit.MINUTES.toMillis(app.prefs.libraryUpdateIntervalMinutes)
        val updateDue = updateInterval == 0L ||
            System.currentTimeMillis() - app.prefs.libraryUpdatedAt >= updateInterval
        if (app.prefs.refreshOnStart && app.prefs.libraryUpdateIntervalMinutes == 0L &&
            updateDue && !refreshedThisProcess
        ) {
            refreshedThisProcess = true
            lifecycleScope.launch {
                runCatching { app.repo.updateLibrary() }
                    .onSuccess { app.prefs.libraryUpdatedAt = System.currentTimeMillis() }
                    .onFailure { Log.e("MainActivity", "Automatic library update failed", it) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun create(id: Int): Fragment = when (id) {
        R.id.nav_library -> LibraryFragment()
        R.id.nav_updates -> UpdatesFragment()
        R.id.nav_history -> HistoryFragment()
        R.id.nav_browse -> BrowseFragment()
        else -> MoreFragment()
    }

    /** Keeps every tab alive (hide/show) so scroll positions and state survive tab switches. */
    private fun show(id: Int) {
        val fm = supportFragmentManager
        val tag = id.toString()
        val tx = fm.beginTransaction()
        tx.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) tx.add(R.id.container, create(id), tag) else tx.show(existing)
        tx.commit()
    }
}
