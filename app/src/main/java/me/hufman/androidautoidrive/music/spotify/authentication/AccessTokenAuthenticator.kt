package me.hufman.androidautoidrive.music.spotify.authentication

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Route
import kotlin.coroutines.CoroutineContext

class AccessTokenAuthenticator(val spotifyOAuth: SpotifyOAuth?, val context: Context): Authenticator, CoroutineScope {
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.IO
	companion object {
		fun attachTokenToRequest(request: Request, accessToken: String?): Request {
			return request.newBuilder().header("Authorization", "Bearer $accessToken").build()
		}
	}

	override fun authenticate(route: Route?, response: okhttp3.Response): Request? = runBlocking {
		if (spotifyOAuth == null) {
			return@runBlocking null
		}
		if (spotifyOAuth.isAuthorized()) {
			val accessToken = if (spotifyOAuth.getNeedsTokenRefresh()) {
				spotifyOAuth.refreshAccessToken()
			} else {
				spotifyOAuth.getAccessToken()
			}
			return@runBlocking attachTokenToRequest(response.request(), accessToken)
		} else {
			spotifyOAuth.createNotAuthorizedNotification(context)
			return@runBlocking null
		}
	}
}