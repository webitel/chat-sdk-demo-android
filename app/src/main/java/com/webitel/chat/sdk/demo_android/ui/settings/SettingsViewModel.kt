package com.webitel.chat.sdk.demo_android.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webitel.chat.sdk.ContactIdentity
import com.webitel.chat.sdk.demo_android.repo.AuthInfo
import com.webitel.chat.sdk.demo_android.repo.AuthRepository
import com.webitel.chat.sdk.demo_android.repo.ChatRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events

    private val _inProgress = MutableSharedFlow<Boolean>()
    val inProgress: SharedFlow<Boolean> = _inProgress

    private val _auth = MutableSharedFlow<AuthInfo>()
    val auth: SharedFlow<AuthInfo> = _auth


    fun save(authInfo: AuthInfo) {
        viewModelScope.launch {
            _inProgress.emit(true)
            val context = getApplication<Application>()
            try {
                ChatRepository.shared.updateConnectInfo(context,
                    authInfo.host,
                    authInfo.token,
                    ContactIdentity(
                        authInfo.userId,
                        authInfo.issuer,
                        authInfo.userName)
                )

                AuthRepository(context)
                    .saveAuthInfo(authInfo)
            }catch (e: Exception) {
                _events.emit(e.message.toString())
            }
            _inProgress.emit(false)
        }
    }


    fun findAuth(context: Context) {
        viewModelScope.launch {
            val authInfo = AuthRepository(context)
                .getAuthInfo()
            if (authInfo != null) {
                _auth.emit(authInfo)
            }
        }
    }
}