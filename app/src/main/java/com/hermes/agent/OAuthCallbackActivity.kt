package com.hermes.agent

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import com.hermes.agent.data.connections.ConnectionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Receives hermesagent://oauth/callback from the browser, hands it to the connection waiting for it, and returns to the app. */
@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {
    @Inject lateinit var connections: ConnectionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(connections::completeSignIn)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
