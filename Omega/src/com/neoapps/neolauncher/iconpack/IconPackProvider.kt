package com.neoapps.neolauncher.iconpack

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.android.launcher3.R
import com.android.launcher3.icons.ClockDrawableWrapper
import com.android.launcher3.icons.IconProvider
import com.android.launcher3.icons.mono.ThemedIconDrawable
import com.android.launcher3.util.MainThreadInitializedObject
import com.neoapps.neolauncher.icons.ClockMetadata
import com.neoapps.neolauncher.icons.CustomAdaptiveIconDrawable
import com.neoapps.neolauncher.util.Config
import com.neoapps.neolauncher.util.minSDK
import com.neoapps.neolauncher.util.prefs

class IconPackProvider(private val context: Context) {
    private val iconPacks = mutableMapOf<String, IconPack?>()
    private val systemIcon = CustomAdaptiveIconDrawable.wrapNonNull(
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
    )

    fun getIconPackOrSystem(packageName: String): IconPack? {
        if (packageName.isEmpty()) {
            return SystemIconPack(context, packageName)
        }
        return getIconPack(packageName)
    }

    fun getIconPack(packageName: String): IconPack? {
        if (packageName.isEmpty()) {
            return null
        }
        return iconPacks.getOrPut(packageName) {
            try {
                CustomIconPack(context, packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun getIconPackList(): List<IconPackInfo> {
        val pm = context.packageManager

        val iconPacks = Config.ICON_INTENTS
            .flatMap { pm.queryIntentActivities(it, 0) }
            .associateBy { it.activityInfo.packageName }
            .mapNotNullTo(mutableSetOf()) { (_, info) ->
                runCatching {
                    IconPackInfo(
                        info.loadLabel(pm).toString(),
                        info.activityInfo.packageName,
                        info.loadIcon(pm)
                    )
                }.getOrNull()
            }
        val defaultIconPack =
            IconPackInfo(context.getString(R.string.icon_pack_default), "", systemIcon)
        val themedIconsInfo = if (minSDK(Build.VERSION_CODES.TIRAMISU))
            IconPackInfo(
                context.getString(R.string.title_themed_icons),
                context.getString(R.string.icon_packs_intent_name),
                ContextCompat.getDrawable(context, R.drawable.ic_launcher)!!,
        ) else null
        return listOfNotNull(
            defaultIconPack,
            themedIconsInfo,
        ) + iconPacks.sortedBy { it.name }
    }

    fun getClockMetadata(iconEntry: IconEntry): ClockMetadata? {
        val iconPack = getIconPackOrSystem(iconEntry.packPackageName) ?: return null
        return iconPack.getClock(iconEntry)
    }

    fun getDrawable(iconEntry: IconEntry, iconDpi: Int, user: UserHandle): Drawable? {
        val iconPack = getIconPackOrSystem(iconEntry.packPackageName) ?: return null
        iconPack.loadBlocking()
        val drawable = iconPack.getIcon(iconEntry, iconDpi) ?: return null
        val shouldTintBackgrounds = context.prefs.profileIconColoredBackground.getValue()
        val clockMetadata =
            if (user == Process.myUserHandle()) iconPack.getClock(iconEntry) else null
        try {
            if (clockMetadata != null) {
                val clockDrawable: ClockDrawableWrapper? =
                    ClockDrawableWrapper.forPackage(context, iconEntry.packPackageName, iconDpi)

                return if (shouldTintBackgrounds) {
                    clockDrawable!!.foreground
                } else {
                    CustomAdaptiveIconDrawable(
                        clockDrawable!!.background,
                        clockDrawable.foreground,
                    )
                }
            }
        } catch (t: Throwable) {
            // Ignore
        }

        return drawable
    }

    private fun wrapThemedData(
        packageManager: PackageManager,
        iconEntry: IconEntry,
        drawable: Drawable,
    ): Drawable {
        if (iconEntry.packPackageName.isEmpty()) return drawable
        val themedColors: IntArray = ThemedIconDrawable.getThemedColors(context)
        return try {
            val res = packageManager.getResourcesForApplication(iconEntry.packPackageName)

            @SuppressLint("DiscouragedApi")
            val resId = res.getIdentifier(iconEntry.name, "drawable", iconEntry.packPackageName)
            val bg: Drawable = themedColors[0].toDrawable()
            val td = IconProvider.ThemeData(res, resId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                drawable is AdaptiveIconDrawable &&
                drawable.monochrome == null
            ) {
                AdaptiveIconDrawable(
                    bg,
                    drawable.foreground,
                    td.loadPaddedDrawable(),
                )
            } else {
                drawable
            }
        } catch (_: PackageManager.NameNotFoundException) {
            drawable
        }
    }

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject(::IconPackProvider)
    }
}

data class IconPackInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable
)