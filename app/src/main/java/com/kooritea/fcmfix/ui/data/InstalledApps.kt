package com.kooritea.fcmfix.ui.data

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.kooritea.fcmfix.util.FcmTrust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class InstalledApp(val packageName: String, val label: String, val includeFcm: Boolean)

object InstalledApps {
    private const val FIREBASE_IID_RECEIVER = "com.google.firebase.iid.FirebaseInstanceIdReceiver"

    /**
     * Every installed package and whether it has an FCM receiver. This takes seconds on a phone
     * with hundreds of system packages, so call it off the main thread. Apps with FCM come first.
     */
    @Suppress("DEPRECATION") // The flag overloads need API 33; minSdk is 29.
    fun scan(pm: PackageManager): List<InstalledApp> {
        val apps = ArrayList<InstalledApp>()
        val flags = PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        for (info in pm.getInstalledPackages(flags)) {
            val appInfo = info.applicationInfo
            val label = appInfo?.loadLabel(pm)?.toString() ?: info.packageName
            if (appInfo != null && !FcmTrust.isAppUid(appInfo.uid)) {
                apps += InstalledApp(info.packageName, label, false)
                continue
            }
            var includeFcm = info.receivers?.any { it.name == FIREBASE_IID_RECEIVER } == true
            if (!includeFcm) {
                val receive = Intent("com.google.android.c2dm.intent.RECEIVE").setPackage(info.packageName)
                val service = Intent("com.google.firebase.MESSAGING_EVENT").setPackage(info.packageName)
                val match = PackageManager.MATCH_DISABLED_COMPONENTS
                includeFcm = pm.queryBroadcastReceivers(receive, match).isNotEmpty() ||
                    pm.queryIntentServices(service, match).isNotEmpty()
            }
            apps += InstalledApp(info.packageName, label, includeFcm)
        }
        val collator = Collator.getInstance(Locale.getDefault())
        return apps.sortedWith(compareBy<InstalledApp> { !it.includeFcm }.thenBy(collator) { it.label })
    }
}

/** App icons for the list, loaded on demand; a placeholder is drawn while one is missing. */
interface IconSource {
    fun cached(packageName: String): ImageBitmap?

    suspend fun load(packageName: String): ImageBitmap?

    object None : IconSource {
        override fun cached(packageName: String): ImageBitmap? = null
        override suspend fun load(packageName: String): ImageBitmap? = null
    }
}

class AppIcons(private val pm: PackageManager, private val sizePx: Int) : IconSource {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    override fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    override suspend fun load(packageName: String): ImageBitmap? = cached(packageName) ?: withContext(Dispatchers.IO) {
        val drawable = try {
            pm.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return@withContext null
        }
        val bitmap = createBitmap(sizePx, sizePx)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap().also { cache.put(packageName, it) }
    }
}
