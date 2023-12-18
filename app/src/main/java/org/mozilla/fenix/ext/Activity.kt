/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.ext

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.navigation.NavDestination
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.intent.ext.getSessionId
import mozilla.components.support.utils.SafeIntent
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity
import org.mozilla.fenix.settings.SupportUtils

/**
 * Attempts to call immersive mode using the View to hide the status bar and navigation buttons.
 *
 * We don't use the equivalent function from Android Components because the stable flag messes
 * with the toolbar. See #1998 and #3272.
 */
@Deprecated(
    message = "Use the Android Component implementation instead.",
    replaceWith = ReplaceWith(
        "enterToImmersiveMode()",
        "mozilla.components.support.ktx.android.view.enterToImmersiveMode",
    ),
)
fun Activity.enterToImmersiveMode() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // This will be addressed on https://github.com/mozilla-mobile/fenix/issues/17804
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}

fun Activity.breadcrumb(
    message: String,
    data: Map<String, String> = emptyMap(),
) {
    components.analytics.crashReporter.recordCrashBreadcrumb(
        Breadcrumb(
            category = this::class.java.simpleName,
            message = message,
            data = data + mapOf(
                "instance" to this.hashCode().toString(),
            ),
            level = Breadcrumb.Level.INFO,
        ),
    )
}

/**
 * Opens Android's Manage Default Apps Settings if possible.
 * Otherwise navigates to the Sumo article indicating why it couldn't open it.
 *
 * @param from fallback direction in case, couldn't open the setting.
 * @param flags fallback flags for when opening the Sumo article page.
 * @param useCustomTab fallback to open the Sumo article in a custom tab.
 */
fun Activity.openSetDefaultBrowserOption(
    from: BrowserDirection = BrowserDirection.FromSettings,
    flags: EngineSession.LoadUrlFlags = EngineSession.LoadUrlFlags.none(),
    useCustomTab: Boolean = false,
) {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            getSystemService(RoleManager::class.java).also {
                if (it.isRoleAvailable(RoleManager.ROLE_BROWSER) && !it.isRoleHeld(
                        RoleManager.ROLE_BROWSER,
                    )
                ) {
                    startActivityForResult(
                        it.createRequestRoleIntent(RoleManager.ROLE_BROWSER),
                        REQUEST_CODE_BROWSER_ROLE,
                    )
                } else {
                    navigateToDefaultBrowserAppsSettings(
                        useCustomTab = useCustomTab,
                        from = from,
                        flags = flags,
                    )
                }
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
            navigateToDefaultBrowserAppsSettings(
                useCustomTab = useCustomTab,
                from = from,
                flags = flags,
            )
        }
        else -> {
            openDefaultBrowserSumoPage(useCustomTab, from, flags)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
private fun Activity.navigateToDefaultBrowserAppsSettings(
    from: BrowserDirection,
    flags: EngineSession.LoadUrlFlags,
    useCustomTab: Boolean,
) {
    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
        putExtra(SETTINGS_SELECT_OPTION_KEY, DEFAULT_BROWSER_APP_OPTION)
        putExtra(
            SETTINGS_SHOW_FRAGMENT_ARGS,
            bundleOf(SETTINGS_SELECT_OPTION_KEY to DEFAULT_BROWSER_APP_OPTION),
        )
    }
    startExternalActivitySafe(
        intent = intent,
        onActivityNotPresent = {
            openDefaultBrowserSumoPage(useCustomTab = useCustomTab, from = from, flags = flags)
        },
    )
}

private fun Activity.openDefaultBrowserSumoPage(
    useCustomTab: Boolean,
    from: BrowserDirection,
    flags: EngineSession.LoadUrlFlags,
) {
    val sumoDefaultBrowserUrl = SupportUtils.getGenericSumoURLForTopic(
        topic = SupportUtils.SumoTopic.SET_AS_DEFAULT_BROWSER,
    )
    if (useCustomTab) {
        startActivity(
            SupportUtils.createSandboxCustomTabIntent(
                context = this,
                url = sumoDefaultBrowserUrl,
            ),
        )
    } else {
        (this as HomeActivity).openToBrowserAndLoad(
            searchTermOrURL = sumoDefaultBrowserUrl,
            newTab = true,
            from = from,
            flags = flags,
        )
    }
}

/**
 * Sets the icon for the back (up) navigation button.
 * @param icon The resource id of the icon.
 */
fun Activity.setNavigationIcon(
    @DrawableRes icon: Int,
) {
    (this as? AppCompatActivity)?.supportActionBar?.let {
        it.setDisplayHomeAsUpEnabled(true)
        it.setHomeAsUpIndicator(icon)
        it.setHomeActionContentDescription(R.string.action_bar_up_description)
    }
}

const val REQUEST_CODE_BROWSER_ROLE = 1
const val SETTINGS_SELECT_OPTION_KEY = ":settings:fragment_args_key"
const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
const val DEFAULT_BROWSER_APP_OPTION = "default_browser"
const val EXTERNAL_APP_BROWSER_INTENT_SOURCE = "CUSTOM_TAB"

/**
 * Depending on the [Activity], maybe derive the source of the given [intent].
 *
 * @param intent the [SafeIntent] to derive the source from.
 */
fun Activity.getIntentSource(intent: SafeIntent): String? = when (this) {
    is ExternalAppBrowserActivity -> EXTERNAL_APP_BROWSER_INTENT_SOURCE
    else -> getHomeIntentSource(intent)
}

private fun getHomeIntentSource(intent: SafeIntent): String? {
    return when {
        intent.isLauncherIntent -> HomeActivity.APP_ICON
        intent.action == Intent.ACTION_VIEW -> "LINK"
        else -> null
    }
}

/**
 * Depending on the [Activity], maybe derive the session ID of the given [intent].
 *
 * @param intent the [SafeIntent] to derive the session ID from.
 */
fun Activity.getIntentSessionId(intent: SafeIntent): String? = when (this) {
    is ExternalAppBrowserActivity -> getExternalAppBrowserIntentSessionId(intent)
    else -> null
}

private fun getExternalAppBrowserIntentSessionId(intent: SafeIntent) = intent.getSessionId()

/**
 * Get the breadcrumb message for the [Activity].
 *
 * @param destination the [NavDestination] required to provide the destination ID.
 */
fun Activity.getBreadcrumbMessage(destination: NavDestination): String = when (this) {
    is ExternalAppBrowserActivity -> getExternalAppBrowserBreadcrumbMessage(destination.id)
    else -> getHomeBreadcrumbMessage(destination.id)
}

private fun Activity.getExternalAppBrowserBreadcrumbMessage(destinationId: Int): String {
    val fragmentName = resources.getResourceEntryName(destinationId)
    return "Changing to fragment $fragmentName, isCustomTab: true"
}

private fun Activity.getHomeBreadcrumbMessage(destinationId: Int): String {
    val fragmentName = resources.getResourceEntryName(destinationId)
    return "Changing to fragment $fragmentName, isCustomTab: false"
}
