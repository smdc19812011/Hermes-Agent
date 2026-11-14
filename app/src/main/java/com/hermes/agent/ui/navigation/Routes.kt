package com.hermes.agent.ui.navigation

sealed class Routes(val route: String) {
    data object Conversations : Routes("conversations")
    data object Chat : Routes("chat/{conversationId}") {
        fun with(id: String) = "chat/$id"
    }
    data object Providers : Routes("providers")
    data object AddEditProvider : Routes("providers/edit?providerId={providerId}") {
        fun add() = "providers/edit"
        fun edit(id: String) = "providers/edit?providerId=$id"
    }
    data object Settings : Routes("settings")
    data object Runtime : Routes("runtime")
    data object Sandbox : Routes("sandbox")
    data object ToolLibrary : Routes("tools")
    data object Agents : Routes("agents")
    data object Connections : Routes("connections")
}
