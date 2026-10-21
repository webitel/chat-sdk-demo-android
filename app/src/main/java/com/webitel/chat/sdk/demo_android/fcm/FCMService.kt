package com.webitel.chat.sdk.demo_android.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.webitel.chat.sdk.demo_android.repo.ChatRepository


class FCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        ChatRepository.shared.registerPushToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        remoteMessage.notification?.let {
            Log.d("FCM", "title: ${it.title}, body: ${it.body}")
        }
    }
}
