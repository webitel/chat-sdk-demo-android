package com.webitel.chat.sdk.demo_android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.webitel.chat.sdk.ConnectionState
import com.webitel.chat.sdk.ContactIdentity
import com.webitel.chat.sdk.demo_android.databinding.ActivityMainBinding
import com.webitel.chat.sdk.demo_android.repo.AuthRepository
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import com.webitel.chat.sdk.demo_android.repo.FileCache
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController
    private lateinit var navView: BottomNavigationView
    private val authRepository by lazy { AuthRepository(this) }

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            ChatRepository.shared.connect()
        }

        override fun onLost(network: Network) {}
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileCache.init(applicationContext)
        setupChatClient()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navView = binding.navView
        setSupportActionBar(binding.topToolbar)

        navController = findNavController(R.id.nav_host_fragment_activity_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_dialogs,
                R.id.navigation_contacts,
                R.id.navigation_settings
            )
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.dialogFragment -> navView.visibility = View.GONE
                else -> navView.visibility = View.VISIBLE
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatRepository.shared.connectionState.collect {
                    updateConnectionLoader(it)
                    setConnectStateUI(it)
                    setTabsEnabled(authRepository.getAuthInfo() != null)
                }
            }
        }

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        val hasAuth = authRepository.getAuthInfo() != null
        setTabsEnabled(hasAuth)
        if (!hasAuth) {
            navView.selectedItemId = R.id.navigation_settings
        }

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
    }


    override fun onResume() {
        super.onResume()
        ChatRepository.shared.connect()
    }


    override fun onPause() {
        super.onPause()
        ChatRepository.shared.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }


    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }


    /** Connection state as the toolbar subtitle — small, under the screen title. */
    private fun setConnectStateUI(state: ConnectionState) {
        supportActionBar?.subtitle = when (state) {
            is ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Connected -> "Connected"
            ConnectionState.Disconnected -> "Disconnected"
            is ConnectionState.Failed -> {
                Toast.makeText(this, state.error.message, Toast.LENGTH_LONG).show()
                "Failed ${state.error.code}"
            }
            is ConnectionState.Reconnecting -> "Reconnecting…"
        }
    }


    private fun updateConnectionLoader(state: ConnectionState) {
        when(state) {
            is ConnectionState.Reconnecting,
                ConnectionState.Connecting -> {
                binding.connectionLoader.visibility = View.VISIBLE
            } else -> {
                binding.connectionLoader.visibility = View.GONE
            }
        }
    }


    private fun setTabsEnabled(enabled: Boolean) {
        navView.menu.findItem(R.id.navigation_dialogs).isEnabled = enabled
        navView.menu.findItem(R.id.navigation_contacts).isEnabled = enabled
    }


    private fun setupChatClient() {
        val auth = authRepository.getAuthInfo() ?: return
        ChatRepository.shared.setupChatClient(
            application, auth.host, auth.token,
            ContactIdentity(auth.userId, auth.issuer, auth.userName)
        )
    }
}