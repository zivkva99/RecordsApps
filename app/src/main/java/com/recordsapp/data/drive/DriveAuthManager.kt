package com.recordsapp.data.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_FILE_SCOPE))
        .build()

    private val client = GoogleSignIn.getClient(context, options)

    fun signInIntent(): Intent = client.signInIntent

    fun currentAccountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        return try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        } catch (e: ApiException) {
            null
        }
    }

    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: error("Not signed in")
        GoogleAuthUtil.getToken(context, account.account!!, "oauth2:$DRIVE_FILE_SCOPE")
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        Tasks.await(client.signOut())
    }
}
