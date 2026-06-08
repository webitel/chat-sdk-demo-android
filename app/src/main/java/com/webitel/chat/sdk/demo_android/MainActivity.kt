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

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            openConnect()
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
                    updateConnectionLouder(it)
                    setConnectStateUI(it)
                    setTabsEnabled(AuthRepository(this@MainActivity).getAuthInfo() != null)
                }
            }
        }

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        val hasAuth = AuthRepository(this).getAuthInfo() != null
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
        openConnect()
    }


    override fun onPause() {
        super.onPause()
        try {
            ChatRepository.shared.closeConnect()
        }catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }


    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }


    private fun openConnect() {
        try {
            ChatRepository.shared.openConnect()
        }catch (e: Exception) {}
    }


    private fun setConnectStateUI(state: ConnectionState) {
        when(state) {
            is ConnectionState.Connecting -> {
                binding.connectionState.text = "Connecting..."
            }
            ConnectionState.Connected -> {
                binding.connectionState.text = "Connected"
            }
            ConnectionState.Disconnected -> {
                binding.connectionState.text = "Disconnected ⚪"
            }
            is ConnectionState.Failed -> {
                binding.connectionState.text = "Failed ${state.error.code} \uD83D\uDD34"
                Toast.makeText(this, state.error.message, Toast.LENGTH_LONG)
                    .show()
            }
            is ConnectionState.Reconnecting -> {
                binding.connectionState.text = "Reconnecting..."
            }
        }
    }


    private fun updateConnectionLouder(state: ConnectionState) {
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
        try {
            val auth = AuthRepository(this@MainActivity)
                .getAuthInfo()

            if (auth != null) {
                ChatRepository.shared.setupChatClient(application, auth.host, auth.token,
                    ContactIdentity(auth.userId, auth.issuer, auth.userName))
            }
        } catch (e: Exception) {}
    }
}