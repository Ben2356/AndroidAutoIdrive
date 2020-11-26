package me.hufman.androidautoidrive.music.spotify

import me.hufman.androidautoidrive.music.spotify.models.User
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * Spotify Web API interface for performing API calls.
 */
interface SpotifyWebApi {
	@GET("me")
	fun getCurrentUser(): Call<User>
}