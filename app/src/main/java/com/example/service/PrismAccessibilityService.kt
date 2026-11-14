package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

class PrismAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _isConnected.value = true
        Log.d(TAG, "PrismAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener; taps are only dispatched on explicit answer detection when Auto-Click is enabled.
    }

    override fun onInterrupt() {
        Log.d(TAG, "PrismAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceRef = null
        _isConnected.value = false
    }

    fun performAutoClickForAnswer(
        answerText: String,
        answerIndex: Int,
        options: List<String>,
        onComplete: (Boolean, String) -> Unit,
    ) {
        mainHandler.post {
            val candidates = buildCandidateTargets(answerText, answerIndex, options)
            if (candidates.isEmpty()) {
                onComplete(false, "No answer text to match")
                return@post
            }

            val roots = collectTargetWindowRoots()
            if (roots.isEmpty()) {
                onComplete(false, "Target app window unavailable")
                return@post
            }

            var matchedNode: AccessibilityNodeInfo? = null
            var matchedLabel = ""

            for (root in roots) {
                val match = findBestMatchingNode(root, candidates)
                if (match != null) {
                    matchedNode = match.first
                    matchedLabel = match.second
                    break
                }
            }

            if (matchedNode == null) {
                onComplete(false, "Matching option not found on screen")
                return@post
            }

            val tapTarget = findClickableAncestorOrSelf(matchedNode) ?: matchedNode
            val bounds = Rect()
            tapTarget.getBoundsInScreen(bounds)

            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
                val clicked = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                onComplete(clicked, if (clicked) "Clicked $matchedLabel" else "Target bounds empty")
                return@post
            }

            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            val path = Path().apply { moveTo(cx, cy) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onComplete(true, "Clicked: $matchedLabel")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        val fallback = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        onComplete(
                            fallback,
                            if (fallback) "Clicked (fallback): $matchedLabel" else "Tap gesture cancelled",
                        )
                    }
                },
                mainHandler,
            )

            if (!dispatched) {
                val fallback = tapTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                onComplete(
                    fallback,
                    if (fallback) "Clicked (action): $matchedLabel" else "Failed to dispatch tap",
                )
            }
        }
    }

    private fun collectTargetWindowRoots(): List<AccessibilityNodeInfo> {
        val ownPackage = applicationContext.packageName
        val roots = mutableListOf<AccessibilityNodeInfo>()

        try {
            for (win in windows) {
                val root = win.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg != null && pkg != ownPackage) {
                    roots.add(root)
                }
            }
        } catch (_: Exception) {
        }

        if (roots.isEmpty()) {
            rootInActiveWindow?.let { active ->
                if (active.packageName?.toString() != ownPackage) {
                    roots.add(active)
                }
            }
        }
        return roots
    }

    private fun findBestMatchingNode(
        root: AccessibilityNodeInfo,
        candidates: List<String>,
    ): Pair<AccessibilityNodeInfo, String>? {
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, allNodes)

        // Pass 1: exact normalized match on node text or contentDescription
        for (target in candidates) {
            for (node in allNodes) {
                val nodeText = normalizeText(node.text?.toString().orEmpty())
                val nodeDesc = normalizeText(node.contentDescription?.toString().orEmpty())
                if (nodeText == target || nodeDesc == target) {
                    return node to target
                }
            }
        }

        // Pass 2: partial/contains match for targets >= 3 chars (pick shortest closest node)
        var bestContainsNode: AccessibilityNodeInfo? = null
        var bestContainsLabel = ""
        var bestLengthDelta = Int.MAX_VALUE

        for (target in candidates) {
            if (target.length < 3) continue
            for (node in allNodes) {
                val nodeText = normalizeText(node.text?.toString().orEmpty())
                val nodeDesc = normalizeText(node.contentDescription?.toString().orEmpty())
                val matchedField = when {
                    nodeText.isNotEmpty() && (nodeText.contains(target) || target.contains(nodeText)) -> nodeText
                    nodeDesc.isNotEmpty() && (nodeDesc.contains(target) || target.contains(nodeDesc)) -> nodeDesc
                    else -> null
                }
                if (matchedField != null) {
                    val delta = kotlin.math.abs(matchedField.length - target.length)
                    if (delta < bestLengthDelta) {
                        bestLengthDelta = delta
                        bestContainsNode = node
                        bestContainsLabel = target
                    }
                }
            }
        }

        return bestContainsNode?.let { it to bestContainsLabel }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out)
        }
    }

    private fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    companion object {
        private const val TAG = "PrismAccessibility"
        private const val TAP_DURATION_MS = 60L

        private var instanceRef: WeakReference<PrismAccessibilityService>? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun getInstance(): PrismAccessibilityService? = instanceRef?.get()

        fun isAccessibilityEnabled(): Boolean = _isConnected.value && instanceRef?.get() != null

        internal fun normalizeText(input: String): String {
            return input
                .trim()
                .replace(Regex("^[A-Ja-j][\\).:\\-]\\s+"), "")
                .replace(Regex("\\s+"), " ")
                .lowercase()
        }

        internal fun buildCandidateTargets(
            answerText: String,
            answerIndex: Int,
            options: List<String>,
        ): List<String> {
            val ordered = linkedSetOf<String>()
            val normAnswer = normalizeText(answerText)
            if (normAnswer.isNotEmpty() && normAnswer != "(tidak yakin)" && normAnswer != "[unclear]") {
                ordered.add(normAnswer)
            }
            if (answerIndex in options.indices) {
                val normOpt = normalizeText(options[answerIndex])
                if (normOpt.isNotEmpty()) {
                    ordered.add(normOpt)
                }
            }
            val rawTrimmed = answerText.trim().lowercase()
            if (rawTrimmed.isNotEmpty() && rawTrimmed != "(tidak yakin)" && rawTrimmed != "[unclear]") {
                ordered.add(rawTrimmed)
            }
            return ordered.toList()
        }
    }
}
