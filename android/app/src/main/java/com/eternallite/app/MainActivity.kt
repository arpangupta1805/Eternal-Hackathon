package com.eternallite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.eternallite.app.ui.CartScreen
import com.eternallite.app.ui.EternalTheme
import com.eternallite.app.ui.HomeScreen
import com.eternallite.app.ui.LocalContainer
import com.eternallite.app.ui.MenuScreen
import com.eternallite.app.ui.OnboardingScreen
import com.eternallite.app.ui.OrderStatusScreen
import com.eternallite.app.ui.OrdersScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as EternalLiteApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                EternalTheme { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val container = LocalContainer.current
    // null while the first read from the database is pending, so the wrong screen never flashes.
    val enrolled by container.settings.enrolled.collectAsState(initial = null)
    when (enrolled) {
        null -> Unit
        false -> OnboardingScreen()
        true -> AppNav()
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStore = { nav.navigate("store/$it") },
                onCart = { nav.navigate("cart") },
                onOrders = { nav.navigate("orders") },
            )
        }
        composable("store/{storeId}", arguments = listOf(navArgument("storeId") { type = NavType.StringType })) { entry ->
            MenuScreen(
                storeId = entry.arguments?.getString("storeId").orEmpty(),
                onBack = { nav.popBackStack() },
                onCart = { nav.navigate("cart") },
            )
        }
        composable("cart") {
            CartScreen(
                onBack = { nav.popBackStack() },
                onPlaced = { orderId ->
                    // Leave the cart behind: Back from the status screen goes home.
                    nav.navigate("order/$orderId") { popUpTo("home") }
                },
            )
        }
        composable("orders") {
            OrdersScreen(onBack = { nav.popBackStack() }, onOrder = { nav.navigate("order/$it") })
        }
        composable("order/{orderId}", arguments = listOf(navArgument("orderId") { type = NavType.StringType })) { entry ->
            OrderStatusScreen(orderId = entry.arguments?.getString("orderId").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
