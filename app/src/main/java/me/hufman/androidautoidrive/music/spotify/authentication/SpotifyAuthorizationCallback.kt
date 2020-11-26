package me.hufman.androidautoidrive.music.spotify.authentication

import net.openid.appauth.TokenResponse

object SpotifyAuthorizationCallback {
	interface Authorize {
		fun onAuthorizationStarted()

		fun onAuthorizationCancelled()

		fun onAuthorizationFailed(error: String?)

		fun onAuthorizationRefused(error: String?)

		fun onAuthorizationSucceed(tokenResponse: TokenResponse?)
	}
}