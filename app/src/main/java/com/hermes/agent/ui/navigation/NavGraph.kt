package com.hermes.agent.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.hermes.agent.ui.agents.AgentsScreen
import com.hermes.agent.ui.chat.ChatScreen
import com.hermes.agent.ui.conversations.ConversationsScreen
import com.hermes.agent.ui.providers.AddEditProviderScreen
import com.hermes.agent.ui.providers.ProvidersScreen
import com.hermes.agent.ui.runtime.RuntimeScreen
import com.hermes.agent.ui.sandbox.SandboxScreen
import com.hermes.agent.ui.toolbox.ToolLibraryScreen
import com.hermes.agent.ui.settings.SettingsScreen

@Composable
fun HermesNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.Conversations.route) {

        composable(Routes.Conversations.route) {
            ConversationsScreen(
                onOpenChat = { id -> navController.navigate(Routes.Chat.with(id)) },
                onOpenProviders = { navController.navigate(Routes.Providers.route) },
                onOpenSettings = { navController.navigate(Routes.Settings.route) },
                onOpenRuntime = { navController.navigate(Routes.Runtime.route) },
                onOpenSandbox = { navController.navigate(Routes.Sandbox.route) }
            )
        }

        composable(
            route = Routes.Chat.route,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStack ->
            val conversationId = backStack.arguments?.getString("conversationId") ?: return@composable
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(Routes.Providers.route) },
                onOpenAgents = { navController.navigate(Routes.Agents.route) }
            )
        }

        composable(Routes.Providers.route) {
            ProvidersScreen(
                onBack = { navController.popBackStack() },
                onAddProvider = { navController.navigate(Routes.AddEditProvider.add()) },
                onEditProvider = { id -> navController.navigate(Routes.AddEditProvider.edit(id)) }
            )
        }

        composable(
            route = Routes.AddEditProvider.route,
            arguments = listOf(navArgument("providerId") {
                type = NavType.StringType; nullable = true; defaultValue = null
            })
        ) { backStack ->
            val providerId = backStack.arguments?.getString("providerId")
            AddEditProviderScreen(
                providerId = providerId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenToolLibrary = { navController.navigate(Routes.ToolLibrary.route) },
                onOpenAgents = { navController.navigate(Routes.Agents.route) }
            )
        }

        composable(Routes.Agents.route) {
            AgentsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ToolLibrary.route) {
            ToolLibraryScreen(
                onBack = { navController.popBackStack() },
                onOpenRuntime = { navController.navigate(Routes.Runtime.route) { launchSingleTop = true } }
            )
        }

        composable(Routes.Runtime.route) {
            RuntimeScreen(
                onBack = { navController.popBackStack() },
                onOpenSandbox = { navController.navigate(Routes.Sandbox.route) }
            )
        }

        composable(Routes.Sandbox.route) {
            SandboxScreen(
                onBack = { navController.popBackStack() },
                onOpenRuntime = { navController.navigate(Routes.Runtime.route) { launchSingleTop = true } }
            )
        }
    }
}
