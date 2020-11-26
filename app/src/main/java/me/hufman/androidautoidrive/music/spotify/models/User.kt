package me.hufman.androidautoidrive.music.spotify.models

import com.google.gson.annotations.SerializedName

/**
 * Current user model returned from the Spotify Web API (/me) call
 */
class User {
	@SerializedName("display_name")
	val displayName: String = ""
}