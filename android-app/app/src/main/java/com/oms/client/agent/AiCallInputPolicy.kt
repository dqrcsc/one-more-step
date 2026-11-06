package com.oms.client.agent

/** A text target stays bound to the same allowed AI-call window through focus and write. */
internal object AiCallInputPolicy {
    data class NodeState(
        val packageName: String?,
        val windowId: Int,
        val visible: Boolean,
        val enabled: Boolean,
        val editable: Boolean,
        val password: Boolean
    )

    data class Target(val packageName: String, val windowId: Int)

    fun select(node: NodeState, allowedPackages: Set<String>, windows: Map<Int, String>): Target? {
        val pkg = node.packageName ?: return null
        if (pkg !in allowedPackages || windows[node.windowId] != pkg ||
            !node.visible || !node.enabled || !node.editable || node.password) return null
        return Target(pkg, node.windowId)
    }

    fun write(
        target: Target,
        refresh: () -> NodeState?,
        windows: () -> Map<Int, String>,
        focus: () -> Boolean,
        setText: () -> Boolean
    ): Boolean {
        fun stillValid(): Boolean {
            val current = refresh() ?: return false
            return select(current, setOf(target.packageName), windows()) == target
        }
        if (!stillValid() || !focus() || !stillValid()) return false
        return setText()
    }
}
