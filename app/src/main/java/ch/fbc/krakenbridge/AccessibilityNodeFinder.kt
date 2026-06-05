package ch.fbc.krakenbridge

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Read-side of the accessibility tree: every lookup, traversal, and
 * node-level operation the vendor adapters and key routing need.
 *
 * Owned by [KrakenAccessibilityService], which passes live providers for
 * the active-window root and the screen size — both change between calls
 * (window switches, configuration changes), so they cannot be captured
 * once at construction.
 */
class AccessibilityNodeFinder(
    private val root: () -> AccessibilityNodeInfo?,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int
) {

    /**
     * Depth-first traversal that returns the first node satisfying [predicate],
     * or null if no node matches. Every `findBy*` helper in this file funnels
     * through here so the recursion only lives in one place.
     */
    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), predicate)?.let { return it }
        }
        return null
    }

    /**
     * Find an AccessibilityNodeInfo by resource ID.
     * Supports both full IDs ("vendor.package:id/some_button") and short IDs
     * ("camera_supermode") — short IDs trigger a fallback recursive search.
     */
    fun byResourceId(resourceId: String): AccessibilityNodeInfo? {
        val root = root() ?: run {
            Log.w(TAG, "No active window root")
            return null
        }

        // Try the exact ID first (works for full package:id/name format)
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        if (nodes.isNotEmpty()) {
            Log.d(TAG, "Found node with resource ID: $resourceId")
            return nodes[0]
        }

        // Fallback: short-ID match (some apps use "camera_supermode" without prefix)
        val node = findNode(root) { n ->
            val id = n.viewIdResourceName ?: return@findNode false
            id == resourceId || id.endsWith("/$resourceId") || id.endsWith(":$resourceId")
        }
        if (node != null) {
            Log.d(TAG, "Found node with short resource ID: $resourceId")
            return node
        }

        Log.w(TAG, "No node found with resource ID: $resourceId")
        return null
    }

    /**
     * Return every node matching [resourceId] (full `package:id/name` form
     * only — short IDs are not supported). Useful when a recycler exposes
     * many siblings sharing the same id and the caller needs to filter by
     * bounds or other attributes.
     */
    fun allByResourceId(resourceId: String): List<AccessibilityNodeInfo> {
        val root = root() ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId(resourceId) ?: emptyList()
    }

    /**
     * Find an AccessibilityNodeInfo by content description.
     * @param exactMatch true = equality; false = case-insensitive contains.
     */
    fun byContentDescription(
        contentDesc: String,
        exactMatch: Boolean = false
    ): AccessibilityNodeInfo? {
        val root = root() ?: run {
            Log.w(TAG, "No active window root")
            return null
        }
        return findNode(root) { n ->
            val desc = n.contentDescription?.toString() ?: return@findNode false
            if (exactMatch) desc == contentDesc
            else desc.contains(contentDesc, ignoreCase = true)
        }?.also { Log.d(TAG, "Found node with content description: ${it.contentDescription}") }
    }

    /**
     * Find a node by its text content.
     * @param exactMatch true = equality; false = case-insensitive contains.
     */
    fun byText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val root = root() ?: return null
        return findNode(root) { n ->
            val nodeText = n.text?.toString() ?: return@findNode false
            if (exactMatch) nodeText == text
            else nodeText.contains(text, ignoreCase = true)
        }?.also { Log.d(TAG, "Found node with text: ${it.text}") }
    }

    /**
     * Find a clickable node in a screen region, optionally constrained by class name.
     * Useful for finding buttons in the bottom action bar by position.
     */
    fun clickableInRegion(
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        className: String? = null
    ): AccessibilityNodeInfo? {
        val root = root() ?: return null
        return findNode(root) { n ->
            if (!n.isClickable) return@findNode false
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (rect.exactCenterX() !in minX..maxX) return@findNode false
            if (rect.exactCenterY() !in minY..maxY) return@findNode false
            className == null || n.className?.toString()?.contains(className, ignoreCase = true) == true
        }?.also {
            val rect = Rect().also { r -> it.getBoundsInScreen(r) }
            Log.d(TAG, "Found clickable in region: class=${it.className}, " +
                    "desc=${it.contentDescription}, id=${it.viewIdResourceName}, bounds=$rect")
        }
    }

    /**
     * Get all clickable nodes in the bottom portion of the screen
     * Returns them in order from left to right
     */
    fun bottomActionBarItems(): List<AccessibilityNodeInfo> {
        val root = root() ?: return emptyList()

        // Bottom action bar is typically in the bottom 15% of the screen
        val minY = screenHeight() * 0.85f
        val maxY = screenHeight().toFloat()

        return collectClickableNodesInRegion(root, 0f, screenWidth().toFloat(), minY, maxY)
            .sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.centerX()
            }
    }

    private fun collectClickableNodesInRegion(
        node: AccessibilityNodeInfo?,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                if (rect.exactCenterX() in minX..maxX && rect.exactCenterY() in minY..maxY) {
                    result.add(n)
                }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(node)
        return result
    }

    /**
     * Click on an AccessibilityNodeInfo
     * @return true if click was performed successfully
     */
    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            Log.w(TAG, "Cannot click null node")
            return false
        }

        if (!node.isClickable) {
            Log.w(TAG, "Node is not clickable: ${node.viewIdResourceName}")
            // Try clicking anyway - some nodes report as non-clickable but still work
        }

        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            Log.i(TAG, "Successfully clicked node: ${node.viewIdResourceName ?: node.contentDescription}")
        } else {
            Log.w(TAG, "Failed to click node: ${node.viewIdResourceName ?: node.contentDescription}")
        }
        return result
    }

    /**
     * Checked state of a node. API 36 deprecated the boolean isChecked in
     * favour of the tri-state getChecked (a partially-checked toggle is not
     * "in the target mode", so it maps to false here).
     */
    fun isChecked(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 36) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }

    /**
     * Get the center coordinates of a node's bounds on screen
     */
    fun center(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.isEmpty) {
            Log.w(TAG, "Node has empty bounds")
            return null
        }

        val centerX = rect.exactCenterX()
        val centerY = rect.exactCenterY()
        Log.d(TAG, "Node center: ($centerX, $centerY), bounds: $rect")
        return Pair(centerX, centerY)
    }

    /**
     * Debug: dump the accessibility tree to logcat. Run this from any foreground
     * camera or gallery (single-photo view, overflow opened, etc.) to discover
     * the actual node identifiers a future vendor adapter needs.
     */
    fun dumpTree() {
        val root = root()
        if (root == null) {
            Log.w(TAG, "DUMP: No active window root")
            return
        }
        Log.i(TAG, "=== ACCESSIBILITY TREE DUMP ===")
        dumpNodeRecursive(root, 0)
        Log.i(TAG, "=== END DUMP ===")
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return

        val indent = "  ".repeat(depth)
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Only log nodes that might be interactive or have useful info
        val resourceId = node.viewIdResourceName
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString()?.substringAfterLast('.')
        val isClickable = node.isClickable
        val text = node.text?.toString()

        // Log if node has ID, content description, is clickable, or has text
        if (resourceId != null || contentDesc != null || isClickable || text != null) {
            val info = buildString {
                append("${indent}[$className]")
                if (resourceId != null) append(" id=$resourceId")
                if (contentDesc != null) append(" desc=\"$contentDesc\"")
                if (text != null) append(" text=\"$text\"")
                if (isClickable) append(" [CLICKABLE]")
                append(" bounds=$rect")
            }
            Log.i(TAG, info)
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            dumpNodeRecursive(node.getChild(i), depth + 1)
        }
    }

    private companion object {
        const val TAG = KrakenAccessibilityService.TAG
    }
}
