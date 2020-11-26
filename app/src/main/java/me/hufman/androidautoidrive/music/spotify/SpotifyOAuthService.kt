package me.hufman.androidautoidrive.music.spotify

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.music.spotify.authentication.SpotifyOAuth

/**
 * Service for the Spotify OAuth session.
 */
class SpotifyOAuthService: Service() {
	companion object {
		var isRunning: Boolean = false
	}

	val binder: IBinder = LocalBinder()
	lateinit var spotifyOAuth: SpotifyOAuth

	override fun onCreate() {
		super.onCreate()

		val clientId = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
				.metaData.getString("com.spotify.music.API_KEY", "unavailable")

		val scopes = listOf(
				"user-modify-playback-state"
		)
		spotifyOAuth = SpotifyOAuth(this, clientId, SpotifyAppController.REDIRECT_URI, scopes)
		isRunning = true
	}

	override fun onBind(intent: Intent?): IBinder {
		return binder
	}

	/**
	 * Returns the [SpotifyOAuth] session.
	 */
	fun getSpotifyOAuthInstance(): SpotifyOAuth {
		return spotifyOAuth
	}

	override fun onDestroy() {
		super.onDestroy()
		if (isRunning) {
			spotifyOAuth.onDestroy()
			isRunning = false
		}
	}

	inner class LocalBinder: Binder() {
		/**
		 * Returns the [SpotifyOAuthService].
		 */
		fun getSpotifyOAuthServiceInstance(): SpotifyOAuthService {
			return this@SpotifyOAuthService
		}
	}
}