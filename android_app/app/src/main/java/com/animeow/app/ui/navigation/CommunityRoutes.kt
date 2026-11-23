package com.animeow.app.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.animeow.app.ui.community.CommunityManagementScreen
import com.animeow.app.ui.community.CommunityPostDetailScreen
import com.animeow.app.ui.community.CommunityBlocklistScreen
import com.animeow.app.ui.community.CommunityFollowListScreen
import com.animeow.app.ui.community.CommunityUserSpaceScreen
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.animeow.app.ui.navigation.appComposable as composable

internal fun NavGraphBuilder.communityRoutes(navController: NavHostController) {
    composable(COMMUNITY_MANAGEMENT_ROUTE) {
        CommunityManagementScreen(onBack = navController::navigateUp)
    }
    composable(
        route = COMMUNITY_POST_DETAIL_ROUTE,
        arguments = listOf(navArgument("postId") { type = NavType.LongType }),
    ) { entry ->
        val postId = entry.arguments?.getLong("postId") ?: return@composable
        CommunityPostDetailScreen(
            postId = postId,
            onBack = navController::navigateUp,
            onUserClick = { userId -> navController.navigate("community/user/$userId") },
        )
    }
    composable(
        route = COMMUNITY_USER_SPACE_ROUTE,
        arguments = listOf(navArgument("userId") { type = NavType.LongType }),
    ) { entry ->
        val userId = entry.arguments?.getLong("userId") ?: return@composable
        CommunityUserSpaceScreen(
            userId = userId,
            onBack = navController::navigateUp,
            onPostClick = { post -> navController.navigate("community/post/${post.id}") },
            onUserClick = { id -> navController.navigate("community/user/$id") },
            onFollowsClick = { id, direction -> navController.navigate("community/user/$id/follows/$direction") },
        )
    }
    composable(
        route = COMMUNITY_FOLLOWS_ROUTE,
        arguments = listOf(
            navArgument("userId") { type = NavType.LongType },
            navArgument("direction") { type = NavType.StringType },
        ),
    ) { entry ->
        val userId = entry.arguments?.getLong("userId") ?: return@composable
        val direction = entry.arguments?.getString("direction") ?: "following"
        CommunityFollowListScreen(
            userId = userId,
            initialDirection = direction,
            onBack = navController::navigateUp,
            onUserClick = { id -> navController.navigate("community/user/$id") },
        )
    }
    composable(COMMUNITY_BLOCKLIST_ROUTE) {
        CommunityBlocklistScreen(onBack = navController::navigateUp)
    }
}
