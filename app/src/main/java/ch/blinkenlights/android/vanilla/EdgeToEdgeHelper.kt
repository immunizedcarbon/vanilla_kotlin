package ch.blinkenlights.android.vanilla

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal object EdgeToEdgeHelper {
    fun apply(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val targetView = if (content.childCount > 0) content.getChildAt(0) else content
        applyInsets(targetView)
    }

    private fun applyInsets(view: View) {
        val initialPadding = (view.getTag(R.id.tag_edge_to_edge_padding) as? InitialPadding)
            ?: InitialPadding(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                .also { view.setTag(R.id.tag_edge_to_edge_padding, it) }

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(
                initialPadding.left + systemBars.left,
                initialPadding.top + systemBars.top,
                initialPadding.right + systemBars.right,
                initialPadding.bottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )
}
