/*
 * Copyright (C) 2015-2023 Adrian Ulrich <adrian@blinkenlights.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package ch.blinkenlights.android.vanilla

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup

class PermissionRequestActivity : Activity() {

    /**
     * The intent to start after acquiring the required permissions
     */
    private var callbackIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callbackIntent = intent.getParcelableExtra(EXTRA_CALLBACK_INTENT, Intent::class.java)

        val allPerms = buildList {
            addAll(getNeededPermissions())
            addAll(getOptionalPermissions())
        }

        requestPermissions(allPerms.toTypedArray(), 0)
    }

    /**
     * Called by Activity after the user interacted with the permission request
     * Will launch the main activity if all permissions were granted, exits otherwise
     *
     * @param requestCode The code set by requestPermissions
     * @param permissions Names of the permissions we got granted or denied
     * @param grantResults Results of the permission requests
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        val neededPerms = getNeededPermissions().toSet()
        var grantedPermissions = 0

        for (i in permissions.indices) {
            if (!neededPerms.contains(permissions[i])) {
                continue
            }
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                grantedPermissions++
            }
        }

        // set as finished before (possibly) killing ourselves
        finish()

        if (grantedPermissions == neededPerms.size) {
            callbackIntent?.let { intent ->
                // start the old intent but ensure to make it a new task & clear any old attached activities
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            }
            // Hack: We *kill* ourselves (while launching the main activity) to get started
            // in a new process.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        private const val EXTRA_CALLBACK_INTENT = "callbackIntent"

        /**
         * Injects a warning that we are missing read permissions into the activity layout
         *
         * @param activity Reference to LibraryActivity
         * @param intent The intent starting the parent activity
         */
        @JvmStatic
        fun showWarning(activity: LibraryActivity, intent: Intent) {
            val inflater = LayoutInflater.from(activity)
            val view = inflater.inflate(R.layout.permission_request, null, false)

            view.setOnClickListener {
                requestPermissions(activity, intent)
            }

            val parent = activity.findViewById<ViewGroup>(R.id.content)
            parent.addView(view, -1)
        }

        /**
         * Launches a permission request dialog if needed
         *
         * @param activity The activity context to use for the permission check
         * @return boolean true if we showed a permission request dialog
         */
        @JvmStatic
        fun requestPermissions(activity: Activity, callbackIntent: Intent): Boolean {
            val havePermissions = havePermissions(activity)

            if (!havePermissions) {
                val intent = Intent(activity, PermissionRequestActivity::class.java)
                intent.putExtra(EXTRA_CALLBACK_INTENT, callbackIntent)
                activity.startActivity(intent)
            }

            return !havePermissions
        }

        /**
         * Checks if all required permissions have been granted
         *
         * @param context The context to use
         * @return boolean true if all permissions have been granted
         */
        @JvmStatic
        fun havePermissions(context: Context): Boolean {
            for (permission in getNeededPermissions()) {
                if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        }

        private fun getNeededPermissions(): List<String> =
            listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )

        private fun getOptionalPermissions(): List<String> =
            listOf(
                Manifest.permission.POST_NOTIFICATIONS,
            )
    }
}
