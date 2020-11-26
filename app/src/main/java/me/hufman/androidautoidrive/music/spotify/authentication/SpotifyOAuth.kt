package me.hufman.androidautoidrive.music.spotify.authentication

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import android.support.customtabs.CustomTabsIntent
import android.support.v4.app.Fragment
import android.support.v4.app.NotificationCompat
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.music.spotify.SpotifyOAuthService
import net.openid.appauth.*
import net.openid.appauth.AuthorizationService.TokenResponseCallback
import net.openid.appauth.ClientAuthentication.UnsupportedAuthenticationMethod
import net.openid.appauth.browser.BrowserMatcher
import net.openid.appauth.browser.BrowserWhitelist
import net.openid.appauth.browser.VersionedBrowserMatcher
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

/**
 * Responsible for handling the Spotify OAuth token and the OAuth persistent state.
 */
class SpotifyOAuth constructor(val context: Context, val clientId: String, val redirectUri: String, val oAuthScopes: List<String>): CoroutineScope {
	override val coroutineContext: CoroutineContext
		get() = Dispatchers.IO

	companion object {
		private const val TAG = "SpotifyOAuth"
		private const val REQUEST_CODE_SPOTIFY_LOGIN = 2356
		private const val CHANNEL_ID = "androidAutoIDriveChannel"
		private const val NOTIFICATION_REQ_ID = 56
	}

	private val authRequest = AtomicReference<AuthorizationRequest>()
	private val authIntent = AtomicReference<CustomTabsIntent>()

	private var executor: ExecutorService = Executors.newSingleThreadExecutor()
	private var authIntentLatch = CountDownLatch(1)

	private val authStateManager: SpotifyAuthStateManager
	private lateinit var authService: AuthorizationService

	private val browserMatcher: BrowserMatcher = BrowserWhitelist(
			VersionedBrowserMatcher.CHROME_CUSTOM_TAB,
			VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB)

	private var authorizationCallbacks: ArrayList<SpotifyAuthorizationCallback.Authorize> = ArrayList()

	private var requestCode = -42
	private var notificationShown: Boolean = false

	init {
		authStateManager = SpotifyAuthStateManager.getInstance(context)
		initializeAppAuth(context)
	}

	/**
	 * Launches an activity displaying the the authorization login page in a custom tab. This is the
	 * entry point of the authorization process.
	 */
	fun launchAuthorizationActivity(context: Context) {
		val intent = Intent(context, AuthorizationActivity::class.java)
		intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
		context.startActivity(intent)
	}

	/**
	 * Creates and displays a notification stating that the web API needs to be authorized.
	 */
	fun createNotAuthorizedNotification(context: Context) {
		if (notificationShown) {
			return
		}

		val notifyIntent = Intent(context, AuthorizationActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
		}
		val notification = NotificationCompat.Builder(context, CHANNEL_ID)
				.setContentTitle(context.getString(R.string.notification_title))
				.setContentText("Spotify Web API needs to be authorized. Click here to authorize.")
				.setSmallIcon(R.drawable.ic_notify)
				.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_REQ_ID, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT))
				.build()
		val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(CHANNEL_ID, "androidAutoIDrive", NotificationManager.IMPORTANCE_HIGH)
			notificationManager.createNotificationChannel(channel)
		}
		notificationManager.notify(NOTIFICATION_REQ_ID, notification)
		notificationShown = true
	}

	/**
	 * Add the class containing the implementation of the Authorize callback methods to be called
	 * during the authorize workflow.
	 */
	fun addAuthorizationCallback(authorizationCallback: SpotifyAuthorizationCallback.Authorize) {
		if (!this.authorizationCallbacks.contains(authorizationCallback)) {
			this.authorizationCallbacks.add(authorizationCallback)
		}
	}

	/**
	 * Removes the class containing the implementation of the Authorize callback methods from the list
	 * of observers.
	 */
	fun removeAuthorizationCallback(authorizationCallback: SpotifyAuthorizationCallback.Authorize) {
		this.authorizationCallbacks.remove(authorizationCallback)
	}

	fun authorize(context: Context, requestCode: Int) {
		this.requestCode = requestCode
		startAuth(context, requestCode, null, null)
	}

	fun authorize(context: Context, completionPendingIntent: PendingIntent?, cancelPendingIntent: PendingIntent?) {
		startAuth(context, 0, completionPendingIntent, cancelPendingIntent)
	}

	/**
	 * Returns whether the [AuthState] contains an authorized session. If this returns false the user
	 * must re-authenticate and grant access to the requested permissions to continue using the Spotify
	 * Web API.
	 */
	fun isAuthorized(): Boolean {
		return authStateManager.currentState.isAuthorized
	}

	/**
	 * Determines whether the access token is considered to have expired. If no refresh token
	 * has been acquired, then this method will always return `false`. A token refresh
	 * can be forced, regardless of the validity of any currently acquired access token, by
	 * calling setNeedsTokenRefresh(boolean).
	 */
	fun getNeedsTokenRefresh(): Boolean {
		return authStateManager.currentState.needsTokenRefresh
	}

	/**
	 * Initializes the authorization service configuration if necessary from the local
	 * static values
	 */
	private fun initializeAppAuth(context: Context) {
		Log.d(TAG, "Initializing AppAuth")
		recreateAuthorizationService(context)
		if (authStateManager.currentState.authorizationServiceConfiguration != null) {
			// configuration is already created, skip to client initialization
			Log.d(TAG, "auth config already established")
			initializeClient()
			return
		}
		val authEndpointUri = Uri.parse("https://accounts.spotify.com/authorize")
		val tokenEndpointUri = Uri.parse("https://accounts.spotify.com/api/token")
		val config = AuthorizationServiceConfiguration(authEndpointUri, tokenEndpointUri)
		authStateManager.replaceState(AuthState(config))
		initializeClient()
	}

	private fun initializeClient() {
		Log.d(TAG, "Using static client ID: $clientId")
		runBlocking {
			initializeAuthRequest()
		}
	}

	private fun initializeAuthRequest() {
		createAuthRequest()
		warmUpBrowser()
	}

	private fun startAuth(context: Context, requestCode: Int, completionPendingIntent: PendingIntent?, cancelPendingIntent: PendingIntent?) {
		authorizationCallbacks.forEach { it.onAuthorizationStarted() }
		executor.submit { doAuth(context, requestCode, completionPendingIntent, cancelPendingIntent) }
	}

	/**
	 * Performs the authorization request
	 */
	private fun doAuth(context: Context, requestCode: Int, completionPendingIntent: PendingIntent?, cancelPendingIntent: PendingIntent?) {
		try {
			authIntentLatch.await()
		} catch (ex: InterruptedException) {
			Log.w(TAG, "Interrupted while waiting for auth intent")
		}

		if (completionPendingIntent != null) {
			authService.performAuthorizationRequest(
					authRequest.get(),
					completionPendingIntent,
					cancelPendingIntent,
					authIntent.get())
		} else {
			val intent = authService.getAuthorizationRequestIntent(
					authRequest.get(),
					authIntent.get())

			if (context is Fragment) {
				context.startActivityForResult(intent, requestCode)
			} else if (context is Activity) {
				context.startActivityForResult(intent, requestCode)
			}
		}
	}

	private fun warmUpBrowser() {
		authIntentLatch = CountDownLatch(1)
		executor.execute {
			Log.d(TAG, "Warming up browser instance for auth request")
			val intentBuilder = authService.createCustomTabsIntentBuilder(authRequest.get().toUri())
			authIntent.set(intentBuilder.build())
			authIntentLatch.countDown()
		}
	}


	private fun createAuthRequest() {
		Log.d(TAG, "Creating auth request")
		val authRequestBuilder = AuthorizationRequest.Builder(
				authStateManager.currentState.authorizationServiceConfiguration!!,
				clientId,
				ResponseTypeValues.CODE,
				Uri.parse(redirectUri))
				.setScopes(oAuthScopes)
		authRequest.set(authRequestBuilder.build())
	}

	private fun recreateAuthorizationService(context: Context) {
		if (this::authService.isInitialized) {
			Log.d(TAG, "Discarding existing AuthService instance")
			authService.dispose()
		}
		authService = createAuthorizationService(context)
		authRequest.set(null)
		authIntent.set(null)
	}

	private fun createAuthorizationService(context: Context): AuthorizationService {
		Log.d(TAG, "Creating authorization service")
		val builder = AppAuthConfiguration.Builder()
		builder.setBrowserMatcher(browserMatcher)
		builder.setConnectionBuilder(DefaultConnectionBuilder.INSTANCE)
		return AuthorizationService(context, builder.build())
	}

	/**
	 * Should be called in ´onStart()´ of the authorization activity.
	 */
	fun onStart() {
		if (executor.isShutdown) {
			executor = Executors.newSingleThreadExecutor()
		}
	}

	/**
	 * Should be called in 'onActivityResult()' of the authorization activity.
	 */
	fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		if (this.requestCode == requestCode) {
			if (resultCode == Activity.RESULT_CANCELED) {
				authorizationCallbacks.forEach { it.onAuthorizationCancelled() }
				return
			}

			data?.let { intent ->
				handleAuthorization(intent)
			}
		}
	}

	private fun handleAuthorization(intent: Intent) {
		val response = AuthorizationResponse.fromIntent(intent)
		val ex = AuthorizationException.fromIntent(intent)

		if (response != null || ex != null) {
			authStateManager.updateAfterAuthorization(response, ex)
		}

		when {
			response?.authorizationCode != null -> {
				// authorization code exchange is required
				authStateManager.updateAfterAuthorization(response, ex)
				exchangeAuthorizationCode(response)
			}
			ex != null -> {
				authorizationCallbacks.forEach { it.onAuthorizationRefused("Authorization flow failed: " + ex.message) }
			}
			else -> {
				authorizationCallbacks.forEach { it.onAuthorizationFailed("No authorization state retained - reauthorization required") }
			}
		}
	}

	/**
	 * Should be called in ´onStop()´ of the authorization activity.
	 */
	fun onStop() {
		executor.shutdownNow()
	}

	/**
	 * Should be called in ´onDestroy()´ of the [SpotifyOAuthService]. This disposes of the authorization
	 * service session.
	 */
	fun onDestroy() {
		if (this::authService.isInitialized) {
			authService.dispose()
		}
	}

	private fun handleRefreshAccessTokenResponse(tokenResponse: TokenResponse?, authException: AuthorizationException?) {
		authStateManager.updateAfterTokenResponse(tokenResponse, authException)
		Log.d(TAG, "OAuth token refresh completed successfully")
	}

	private fun performTokenRequest(request: TokenRequest, callback: TokenResponseCallback) {
		val clientAuthentication: ClientAuthentication

		clientAuthentication = try {
			authStateManager.currentState.clientAuthentication
		} catch (ex: UnsupportedAuthenticationMethod) {
			Log.d(TAG, "Token request cannot be made, client authentication " +
					"for the token endpoint could not be constructed (%s)", ex)
			authorizationCallbacks.forEach { it.onAuthorizationFailed("Client authentication method is unsupported") }
			return
		}

		authService.performTokenRequest(request, clientAuthentication, callback)
	}

	/**
	 * Refreshes the access token using the refresh token stored in the [AuthState]. If the process
	 * succeeds the new access token will be returned when the request call is finished.
	 */
	suspend fun refreshAccessToken(): String? {
		Log.d(TAG, "OAuth token refresh started")
		val clientAuthentication = try {
			authStateManager.currentState.clientAuthentication
		} catch (ex: UnsupportedAuthenticationMethod) {
			Log.d(TAG, "Token request cannot be made, client authentication " +
					"for the token endpoint could not be constructed (%s)", ex)
			return null
		}
		return suspendCancellableCoroutine { continuation ->
			authService.performTokenRequest(authStateManager.currentState.createTokenRefreshRequest(), clientAuthentication) { tokenResponse: TokenResponse?, authException: AuthorizationException? ->
				handleRefreshAccessTokenResponse(tokenResponse, authException)
				if (authException != null) {
					continuation.resumeWithException(authException)
				}
				continuation.resume(tokenResponse?.accessToken) {
					throw it
				}
			}
		}
	}

	/**
	 * Returns the current available access token from the last made token response.
	 */
	fun getAccessToken(): String? {
		return authStateManager.currentState.accessToken
	}

	private fun exchangeAuthorizationCode(authorizationResponse: AuthorizationResponse) {
		performTokenRequest(authorizationResponse.createTokenExchangeRequest()) { tokenResponse: TokenResponse?, authException: AuthorizationException? ->
			handleCodeExchangeResponse(tokenResponse, authException)
		}
	}

	private fun handleCodeExchangeResponse(tokenResponse: TokenResponse?, authException: AuthorizationException?) {
		authStateManager.updateAfterTokenResponse(tokenResponse, authException)

		if (!isAuthorized()) {
			val message = ("Authorization Code exchange failed"
					+ if (authException != null) authException.error else "")

			runBlocking {
				authorizationCallbacks.forEach { it.onAuthorizationRefused(message) }
			}
		} else {
			runBlocking {
				authorizationCallbacks.forEach { it.onAuthorizationSucceed(authStateManager.currentState.lastTokenResponse) }
			}
		}
	}

	fun logOut() {
		// discard the authorization and token state, but retain the configuration and
		// dynamic client registration (if applicable), to save from retrieving them again.
		val currentState: AuthState = authStateManager.currentState
		val serviceConfiguration = currentState.authorizationServiceConfiguration

		serviceConfiguration?.let {
			val clearedState = AuthState(it)
			if (currentState.lastRegistrationResponse != null) {
				clearedState.update(currentState.lastRegistrationResponse)
			}
			authStateManager.replaceState(clearedState)
		}
	}

	/**
	 * Authorization activity class that is used to perform the authorization and then process the
	 * result, updating the [AuthState]. The activity will be closed once the process has either been
	 * cancelled, refused, or succeeds.
	 */
	class AuthorizationActivity: Activity(), SpotifyAuthorizationCallback.Authorize {
		companion object {
			const val TAG = "AuthorizationActivity"
		}

		lateinit var spotifyOAuth: SpotifyOAuth
		val oauthServiceConnector = object: ServiceConnection {
			override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
				Log.d(TAG, "SpotifyOAuthService connected")
				val localBinder = service as SpotifyOAuthService.LocalBinder
				val spotifyOAuthService = localBinder.getSpotifyOAuthServiceInstance()
				spotifyOAuth = spotifyOAuthService.getSpotifyOAuthInstance()
				spotifyOAuth.addAuthorizationCallback(this@AuthorizationActivity)
				spotifyOAuth.authorize(this@AuthorizationActivity, REQUEST_CODE_SPOTIFY_LOGIN)
			}

			override fun onServiceDisconnected(name: ComponentName?) {
				Log.d(TAG, "SpotifyOAuthService disconnected")
			}
		}

		override fun onStart() {
			super.onStart()
			spotifyOAuth.onStart()
		}

		override fun onCreate(savedInstanceState: Bundle?) {
			super.onCreate(savedInstanceState)

			val spotifyOAuthServiceIntent = Intent(this, SpotifyOAuthService::class.java)
			this.bindService(spotifyOAuthServiceIntent, oauthServiceConnector, BIND_AUTO_CREATE)
		}

		override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
			super.onActivityResult(requestCode, resultCode, data)

			// At this point it is authorized but we don't have access token yet.
			// We get it when onAuthorizationSucceed() is called
			spotifyOAuth.onActivityResult(requestCode, resultCode, data)
		}

		override fun onStop() {
			super.onStop()
			spotifyOAuth.onStop()
		}

		override fun onDestroy() {
			super.onDestroy()
			spotifyOAuth.removeAuthorizationCallback(this)
		}

		override fun onAuthorizationStarted() {
			Log.d(TAG, "Authorization started")
		}

		override fun onAuthorizationCancelled() {
			Log.d(TAG, "Authorization cancelled")
			finishActivity()
		}

		override fun onAuthorizationFailed(error: String?) {
			Log.d(TAG, "Authorization failed with the error: $error")
			finishActivity()
		}

		override fun onAuthorizationRefused(error: String?) {
			Log.d(TAG, "Authorization refused with the error: $error")
			finishActivity()
		}

		override fun onAuthorizationSucceed(tokenResponse: TokenResponse?) {
			Log.d(TAG, "Authorization process completed successfully. AuthState updated")
			clearNotAuthorizedNotification()
			finishActivity()
		}

		/**
		 * Clears the notification that is displayed when the user is not authenticated.
		 */
		private fun clearNotAuthorizedNotification() {
			val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.cancel(NOTIFICATION_REQ_ID)
		}

		/**
		 * Finishes the activity by unbinding the OAuth service first to prevent leaks.
		 */
		private fun finishActivity() {
			unbindService(oauthServiceConnector)
			finish()
		}
	}
}