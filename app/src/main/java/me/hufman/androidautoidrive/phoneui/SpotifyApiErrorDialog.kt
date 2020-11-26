package me.hufman.androidautoidrive.phoneui

import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.support.v4.app.DialogFragment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.spotify.SpotifyOAuthService
import me.hufman.androidautoidrive.music.spotify.authentication.SpotifyAuthorizationCallback
import me.hufman.androidautoidrive.music.spotify.authentication.SpotifyOAuth
import net.openid.appauth.TokenResponse

class SpotifyApiErrorDialog: DialogFragment(), SpotifyAuthorizationCallback.Authorize {
	companion object {
		const val TAG = "SpotifyApiErrorDialog"
		const val EXTRA_CLASSNAME = "classname"
		const val EXTRA_MESSAGE = "message"
		const val EXTRA_WEB_API_AUTHORIZED = "webApiAuthorized"
	}

	lateinit var webApiMsgTextView: TextView
	lateinit var authorizeButton: Button
	lateinit var spotifyOAuth: SpotifyOAuth

	val oauthServiceConnector = object: ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
			Log.d(TAG, "SpotifyOAuthService connected")
			val localBinder = service as SpotifyOAuthService.LocalBinder
			val spotifyOAuthService = localBinder.getSpotifyOAuthServiceInstance()
			spotifyOAuth = spotifyOAuthService.getSpotifyOAuthInstance()
			spotifyOAuth.addAuthorizationCallback(this@SpotifyApiErrorDialog)
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			Log.d(TAG, "SpotifyOAuthService disconnected")
		}
	}

	override fun onStart() {
		super.onStart()

		val spotifyOAuthServiceIntent = Intent(context, SpotifyOAuthService::class.java)
		context?.bindService(spotifyOAuthServiceIntent, oauthServiceConnector, Context.BIND_AUTO_CREATE)
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val builder = AlertDialog.Builder(activity)
		val view = activity?.layoutInflater?.inflate(R.layout.dialog_error, null)
		builder.setView(view)

		val excClassname = arguments?.getString(EXTRA_CLASSNAME) ?: ""
		val errorMessage = arguments?.getString(EXTRA_MESSAGE) ?: ""
		val hint = when(excClassname) {
			"CouldNotFindSpotifyApp" -> getString(R.string.musicAppNotes_spotify_apiNotFound)
			"UserNotAuthorizedException" -> if (errorMessage.contains("AUTHENTICATION_SERVICE_UNAVAILABLE")) {
				getString(R.string.musicAppNotes_spotify_apiUnavailable)
			} else ""
			else -> ""
		}
		val message = "$excClassname\n$errorMessage\n\n$hint"
		val appRemoteErrorTextView = view?.findViewById<TextView>(R.id.txtAppRemoteError)
		appRemoteErrorTextView?.text = if (excClassname.isNotBlank()) {
			message
		} else {
			""
		}

		val spotifyAppRemoteLastErrorTitleTextView = view?.findViewById<TextView>(R.id.txtSpotifyAppRemoteLastErrorTitle)
		spotifyAppRemoteLastErrorTitleTextView?.visible = !appRemoteErrorTextView?.text.isNullOrBlank()

		val webApiAuthorized = arguments?.getBoolean(EXTRA_WEB_API_AUTHORIZED)
		if (webApiAuthorized == null) {
			webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_loading)
			authorizeButton.visible = false
		} else if (!webApiAuthorized) {
			webApiMsgTextView = view?.findViewById(R.id.txtWebApiAuthorizationMsg)!!
			webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_needed)

			authorizeButton = view.findViewById(R.id.btnAuthorizeWebApi)
			authorizeButton.visible = true
			authorizeButton.setOnClickListener(object: View.OnClickListener {
				override fun onClick(v: View?) {
					spotifyOAuth.launchAuthorizationActivity(context!!)
				}
			})
		} else {
			webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_success)
			authorizeButton.visible = false
		}

		view?.findViewById<Button>(R.id.btnOk)?.setOnClickListener(object: View.OnClickListener {
			override fun onClick(v: View?) {
				dismiss()
			}
		})
		return builder.create()
	}

	override fun onDestroy() {
		super.onDestroy()
		spotifyOAuth.removeAuthorizationCallback(this)
		context?.unbindService(oauthServiceConnector)
	}

	override fun onAuthorizationStarted() {
		Log.d(TAG, "Starting authorization flow")
	}

	override fun onAuthorizationCancelled() {
		webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_canceled)
	}

	override fun onAuthorizationFailed(error: String?) {
		webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_failed)
	}

	override fun onAuthorizationRefused(error: String?) {
		webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_refused)
	}

	override fun onAuthorizationSucceed(tokenResponse: TokenResponse?) {
		webApiMsgTextView.text = getString(R.string.txt_spotify_api_authorization_success)
		authorizeButton.visible = false
	}
}