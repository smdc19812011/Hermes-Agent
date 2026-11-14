package com.hermes.agent.data.connections

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth 2 authorization-code sign-in with PKCE, plus the discovery and client registration MCP servers use. Blocking. */
object OAuth {
    const val REDIRECT_URI = "hermesagent://oauth/callback"
    private val random = SecureRandom()

    fun randomToken(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    fun authorizeUrl(o: OAuthState, state: String, verifier: String): String = o.authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", o.clientId)
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", challenge(verifier))
        .addQueryParameter("code_challenge_method", "S256")
        .apply {
            if (o.scopes.isNotBlank()) addQueryParameter("scope", o.scopes.trim())
            if (o.resource.isNotBlank()) addQueryParameter("resource", o.resource)
        }.build().toString()

    /** `resource_metadata="…"` from a WWW-Authenticate header. */
    fun resourceMetadataUrl(wwwAuthenticate: String?): String? =
        wwwAuthenticate?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    /**
     * Fills in endpoints for an MCP server: protected-resource metadata (from the 401 or the well-known path) names the
     * authorization server, whose metadata gives the endpoints; with no client ID, the app registers itself (RFC 7591).
     */
    fun discover(http: OkHttpClient, serverUrl: String, wwwAuthenticate: String?, current: OAuthState): OAuthState {
        val origin = serverUrl.toHttpUrl().resolve("/")!!.toString().trimEnd('/')
        val prm = (listOfNotNull(resourceMetadataUrl(wwwAuthenticate)) + "$origin/.well-known/oauth-protected-resource")
            .firstNotNullOfOrNull { getJson(http, it) }
        val issuer = prm?.getAsJsonArray("authorization_servers")?.firstOrNull()?.asString?.trimEnd('/') ?: origin
        val asUrl = issuer.toHttpUrl()
        val path = asUrl.encodedPath.trimEnd('/')
        val base = asUrl.resolve("/")!!.toString().trimEnd('/')
        val meta = listOf(
            "$base/.well-known/oauth-authorization-server$path",
            "$base/.well-known/openid-configuration$path",
            "$issuer/.well-known/openid-configuration"
        ).distinct().firstNotNullOfOrNull { getJson(http, it) }
            ?: throw IOException("Couldn't find the sign-in server for $serverUrl. Enter the OAuth URLs by hand.")
        val scopes = current.scopes.ifBlank { prm?.getAsJsonArray("scopes_supported")?.joinToString(" ") { it.asString }.orEmpty() }
        var o = current.copy(
            authUrl = meta.get("authorization_endpoint")?.asString ?: throw IOException("The sign-in server has no authorization endpoint."),
            tokenUrl = meta.get("token_endpoint")?.asString ?: throw IOException("The sign-in server has no token endpoint."),
            scopes = scopes,
            resource = prm?.get("resource")?.asString ?: serverUrl
        )
        if (o.clientId.isBlank()) {
            val reg = meta.get("registration_endpoint")?.asString
                ?: throw IOException("This server needs a client ID. Register an app with the provider and enter its client ID.")
            o = register(http, reg, o)
        }
        return o
    }

    private fun register(http: OkHttpClient, endpoint: String, o: OAuthState): OAuthState {
        val body = JsonObject().apply {
            addProperty("client_name", "Hermes Agent for Android")
            add("redirect_uris", JsonArray().apply { add(REDIRECT_URI) })
            add("grant_types", JsonArray().apply { add("authorization_code"); add("refresh_token") })
            add("response_types", JsonArray().apply { add("code") })
            addProperty("token_endpoint_auth_method", "none")
            if (o.scopes.isNotBlank()) addProperty("scope", o.scopes)
        }
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Client registration failed: HTTP ${r.code} ${text.take(200)}")
            val json = JsonParser.parseString(text).asJsonObject
            return o.copy(clientId = json.get("client_id").asString, clientSecret = json.get("client_secret")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        }
    }

    fun exchangeCode(http: OkHttpClient, o: OAuthState, code: String): OAuthState = token(http, o, FormBody.Builder()
        .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT_URI).add("code_verifier", o.pendingVerifier))
        .copy(pendingState = "", pendingVerifier = "")

    fun refresh(http: OkHttpClient, o: OAuthState): OAuthState {
        if (o.refreshToken.isBlank()) throw IOException("The sign-in expired. Sign in again under Settings → Connections.")
        return token(http, o, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", o.refreshToken))
    }

    private fun token(http: OkHttpClient, o: OAuthState, form: FormBody.Builder): OAuthState {
        form.add("client_id", o.clientId)
        if (o.clientSecret.isNotBlank()) form.add("client_secret", o.clientSecret)
        if (o.resource.isNotBlank()) form.add("resource", o.resource)
        val request = Request.Builder().url(o.tokenUrl).header("Accept", "application/json").post(form.build()).build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (!r.isSuccessful || json?.get("access_token") == null) {
                val why = json?.get("error_description")?.asString ?: json?.get("error")?.asString ?: text.take(200)
                throw IOException("Sign-in failed (HTTP ${r.code}): $why")
            }
            val expiresIn = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong
            return o.copy(
                accessToken = json.get("access_token").asString,
                refreshToken = json.get("refresh_token")?.takeIf { it.isJsonPrimitive }?.asString ?: o.refreshToken,
                expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000 } ?: 0
            )
        }
    }

    private fun getJson(http: OkHttpClient, url: String): JsonObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) null else JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
        }
    }.getOrNull()
}
