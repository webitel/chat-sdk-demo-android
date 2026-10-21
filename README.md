# Chat SDK Android Demo

This demo project shows a simple example of how to integrate the Chat
SDK into an Android application using Kotlin, Coroutines and Flow.

The SDK itself is callback-based to support both Java and Kotlin
projects. All SDK calls are kept in one place (`ChatRepository` and its
per-feature repositories in `repo/`), callbacks are turned into suspend
functions with `awaitResult`, and the UI observes state through
`StateFlow`. Lines that call the SDK are marked with `// SDK:` comments.

## Features demonstrated

-   SDK initialization
-   Token-based (JWT) and ContactIdentity authentication
-   Loading contacts
-   Loading dialogs with unread counters
-   Sending messages and replies
-   Message history with pagination
-   Receiving real-time updates
-   Optimistic UI updates
-   Connection state handling
-   Sync of changes missed while offline
-   Read receipts and delivery status
-   Typing indicators
-   Message reactions
-   Editing and deleting messages
-   Message search
-   File upload and download with progress
-   Interactive messages (keyboard buttons)
-   Push notifications (FCM)
-   Simple local persistence using SharedPreferences

## Documentation

Documentation is available in the [SDK repository](https://github.com/webitel/chat-sdk-android/tree/main/docs).