package me.hufman.androidautoidrive.phoneui

import android.arch.lifecycle.ViewModelProviders
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.*
import android.os.Bundle
import android.os.IBinder
import android.support.v4.app.Fragment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import kotlinx.android.synthetic.main.music_nowplaying.*
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.Utils
import me.hufman.androidautoidrive.getThemeColor
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.androidautoidrive.music.controllers.SpotifyAppController
import me.hufman.androidautoidrive.music.spotify.SpotifyOAuthService
import me.hufman.androidautoidrive.music.spotify.authentication.SpotifyOAuth

class MusicNowPlayingFragment: Fragment() {
	companion object {
		const val TAG = "MusicNowPlayingFragment"
		const val ARTIST_ID = "150.png"
		const val ALBUM_ID = "148.png"
		const val SONG_ID = "152.png"
		const val PLACEHOLDER_ID = "147.png"
	}

	lateinit var musicController: MusicController
	lateinit var placeholderCoverArt: Bitmap
	var spotifyOAuth: SpotifyOAuth? = null
	var isSpotifyOAuthServiceBound: Boolean = false
	val oauthServiceConnector = object: ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
			Log.d(TAG, "SpotifyOAuthService connected")
			val localBinder = service as SpotifyOAuthService.LocalBinder
			val spotifyOAuthService = localBinder.getSpotifyOAuthServiceInstance()
			spotifyOAuth = spotifyOAuthService.getSpotifyOAuthInstance()
			isSpotifyOAuthServiceBound = true
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			Log.d(TAG, "SpotifyOAuthService disconnected")
			isSpotifyOAuthServiceBound = false
		}
	}

	fun onActive() {
		musicController.listener = Runnable { redraw() }
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		return inflater.inflate(R.layout.music_nowplaying, container, false)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		val viewModel = ViewModelProviders.of(requireActivity()).get(MusicActivityModel::class.java)
		val musicController = viewModel.musicController ?: return
		this.musicController = musicController

		imgArtist.setImageBitmap(viewModel.icons[ARTIST_ID])
		imgAlbum.setImageBitmap(viewModel.icons[ALBUM_ID])
		imgSong.setImageBitmap(viewModel.icons[SONG_ID])
		imgArtist.colorFilter = Utils.getIconMask(context!!.getThemeColor(android.R.attr.textColorSecondary))
		imgAlbum.colorFilter = Utils.getIconMask(context!!.getThemeColor(android.R.attr.textColorSecondary))
		imgSong.colorFilter = Utils.getIconMask(context!!.getThemeColor(android.R.attr.textColorSecondary))
		placeholderCoverArt = viewModel.icons[PLACEHOLDER_ID]!!

		btnPrevious.setOnClickListener { musicController.skipToPrevious() }
		btnPlay.setOnClickListener {
			if (musicController.getPlaybackPosition().playbackPaused) {
				musicController.play()
			} else {
				musicController.pause()
			}
		}
		btnNext.setOnClickListener { musicController.skipToNext() }
		musicController.listener = Runnable { redraw() }

		seekProgress.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
			override fun onProgressChanged(seekbar: SeekBar?, value: Int, fromUser: Boolean) {
				if (fromUser) {
					musicController.seekTo(value * 1000L)
				}
			}

			override fun onStartTrackingTouch(p0: SeekBar?) {
			}
			override fun onStopTrackingTouch(p0: SeekBar?) {
			}

		})
	}

	override fun onResume() {
		super.onResume()
		redraw()
	}

	fun redraw() {
		if (!isVisible) return

		// only want this to bind to the SpotifyOAuthService if it is already running and not start the Service
		if (SpotifyOAuthService.isRunning && !isSpotifyOAuthServiceBound) {
			val spotifyOAuthServiceIntent = Intent(context, SpotifyOAuthService::class.java)
			context?.bindService(spotifyOAuthServiceIntent, oauthServiceConnector, Context.BIND_AUTO_CREATE)
		}

		val metadata = musicController.getMetadata()
		if (metadata?.coverArt != null) {
			imgCoverArt.setImageBitmap(metadata.coverArt)
		} else {
			imgCoverArt.setImageBitmap(placeholderCoverArt)
		}
		txtArtist.text = if (musicController.isConnected()) { metadata?.artist ?: "" } else { getString(R.string.nowplaying_notconnected) }
		txtAlbum.text = metadata?.album
		txtSong.text = metadata?.title ?: getString(R.string.nowplaying_unknown)

		if (musicController.getPlaybackPosition().playbackPaused) {
			btnPlay.setImageResource(android.R.drawable.ic_media_play)
		} else {
			btnPlay.setImageResource(android.R.drawable.ic_media_pause)
		}
		val position = musicController.getPlaybackPosition()
		seekProgress.progress = (position.getPosition() / 1000).toInt()
		seekProgress.max = (position.maximumPosition / 1000).toInt()

		// show any spotify app remote errors
		val fragmentManager = activity?.supportFragmentManager
		val spotifyError = musicController.connectors.filterIsInstance<SpotifyAppController.Connector>().firstOrNull()?.lastError

		val isWebApiAuthorized = spotifyOAuth?.isAuthorized()

		if (fragmentManager != null && (spotifyError != null || isWebApiAuthorized == false)) {
			imgError.visible = true
			imgError.setOnClickListener {
				val arguments = Bundle().apply {
					putString(SpotifyApiErrorDialog.EXTRA_CLASSNAME, spotifyError?.javaClass?.simpleName)
					putString(SpotifyApiErrorDialog.EXTRA_MESSAGE, spotifyError?.message)
					if (isWebApiAuthorized != null) {
						putBoolean(SpotifyApiErrorDialog.EXTRA_WEB_API_AUTHORIZED, isWebApiAuthorized)
					}
				}
				SpotifyApiErrorDialog().apply {
					setArguments(arguments)
					show(fragmentManager, "spotify_error")
				}
			}
		} else {
			imgError.visible = false
			imgError.setOnClickListener(null)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		if (isSpotifyOAuthServiceBound) {
			context?.unbindService(oauthServiceConnector)
			isSpotifyOAuthServiceBound = false
		}
	}
}