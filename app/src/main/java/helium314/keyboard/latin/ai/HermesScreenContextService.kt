// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lightweight screen-context reader for Hermes Keyboard.
 *
 * When enabled in Android Accessibility (`settings put secure enabled_accessibility_services`),
 * it allows the Hermes AI Assist button to read the active chat thread on screen when the
 * compose field is empty, so tapping the Hermes button drafts a reply in one click without
 * copying any message first.
 */
class HermesScreenContextService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // On-demand traversal happens when the user taps the Hermes button.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: HermesScreenContextService? = null

        private val IGNORED_EXACT = setOf(
            "Send", "SMS", "MMS", "RCS", "Text message", "RCS message",
            "Write a message", "Message", "Type a message", "Add attachment",
            "Emoji", "Voice message", "More options", "Navigate up", "Search",
            "Call", "Video call", "Delivered", "Read", "Sent", "Now"
        )

        @JvmStatic
        fun captureVisibleConversation(): String? {
            val svc = instance ?: return null
            return try {
                val roots = mutableListOf<AccessibilityNodeInfo>()
                svc.rootInActiveWindow?.let { roots.add(it) }
                try {
                    for (win in svc.windows) {
                        val r = win.root ?: continue
                        val pkg = r.packageName?.toString() ?: ""
                        if (pkg.startsWith("helium314.keyboard") || pkg == "com.android.systemui") continue
                        if (roots.none { it == r }) roots.add(r)
                    }
                } catch (_: Exception) {}

                val items = mutableListOf<Pair<Int, String>>()
                val seen = LinkedHashSet<String>()
                for (root in roots) {
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg.startsWith("helium314.keyboard") || pkg == "com.android.systemui") continue
                    collectNodes(root, items, seen)
                }
                if (items.isEmpty()) return null
                items.sortBy { it.first }
                val recent = items.takeLast(16).map { it.second }
                recent.joinToString("\n").trim().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        private fun collectNodes(
            node: AccessibilityNodeInfo?,
            out: MutableList<Pair<Int, String>>,
            seen: MutableSet<String>
        ) {
            if (node == null || !node.isVisibleToUser) return
            if (!node.isEditable) {
                val rawText = node.text?.toString()?.trim().orEmpty()
                val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
                val candidate = when {
                    rawText.length >= 2 -> rawText
                    rawDesc.length >= 12 -> rawDesc
                    else -> ""
                }
                if (candidate.isNotEmpty() &&
                    candidate !in IGNORED_EXACT &&
                    !candidate.matches(Regex("^\\d{1,2}:\\d{2}(\\s?[AP]M)?$", RegexOption.IGNORE_CASE))
                ) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.height() > 0 && rect.top >= 0 && seen.add(candidate)) {
                        out.add(rect.top to candidate)
                    }
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                collectNodes(node.getChild(i), out, seen)
            }
        }
    }
}
