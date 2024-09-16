package com.example.firebaseauthdemoapp

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.firebaseauthdemoapp.model.User
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID

class AuthViewModel: ViewModel() {
    private val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState

    val user = MutableStateFlow<User>(User("", "", "", "", "", ""))

    init {
        checkAuthStatus()
    }

    fun checkAuthStatus() {
        if (auth.currentUser == null) {
            _authState.value = AuthState.Unauthenticated
        } else {
            _authState.value = AuthState.Authenticated
        }
    }

    fun login(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error("Email and password must not be empty")
            return
        }

        _authState.value = AuthState.Loading
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    _authState.value = AuthState.Authenticated
                } else {
                    _authState.value = AuthState.Error(task.exception?.message ?: "An unknown error occurred")
                }
            }
    }

    fun signup(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error("Email and password must not be empty")
            return
        }

        _authState.value = AuthState.Loading
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    _authState.value = AuthState.Authenticated
                } else {
                    _authState.value = AuthState.Error(task.exception?.message ?: "An unknown error occurred")
                }
            }
    }

    fun handleGoogleSignIn(context: Context) {
        viewModelScope.launch {
            // Collect the result of the Google Sign-In precess
            googleSignIn(context).collect {result ->
                result.fold(
                    onSuccess = { authResult ->
                        val currentUser = authResult.user
                        if (currentUser != null) {
                            user.value = User(currentUser.uid, currentUser.displayName ?: "", currentUser.photoUrl.toString(), currentUser.email!!, "", "")
                            // Show success message
                            Toast.makeText(
                                context,
                                "Account created successfully!",
                                Toast.LENGTH_LONG
                            ).show()
                            _authState.value = AuthState.Authenticated
                        }
                    },
                    onFailure = { e ->
                        // Show error message
                        Toast.makeText(
                            context,
                            "Failed to create account: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        _authState.value = AuthState.Error(e.message ?: "An unknown error occurred")
                    }
                )
            }
        }
    }

    private suspend fun googleSignIn(context: Context): Flow<Result<AuthResult>> {
        return callbackFlow {
            try {
                // Initialize Credential Manager
                val credentialManager: CredentialManager = CredentialManager.create(context)

                // Generate a nonce (a random number used once) for security
                val ranNonce: String = UUID.randomUUID().toString()
                val bytes: ByteArray = ranNonce.toByteArray()
                val md: MessageDigest = MessageDigest.getInstance("SHA-256")
                val digest: ByteArray = md.digest(bytes)
                val hashedNonce: String = digest.fold("") { str, it -> str + "%02x".format(it) }

                val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(context.getString(R.string.default_web_client_id))
                    .setAutoSelectEnabled(false)
                    .setNonce(hashedNonce)
                    .build()

                // Create a credential request with the Google ID option
                val request: GetCredentialRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential

                // Check if the received credential is valid Google ID Token
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        // Extract the Google ID Token from the credential
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        // Create an auth credential using the Google ID Token
                        val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                        // Sign in with Firebase Auth using the Google Auth Credential
                        val authResult: AuthResult = auth.signInWithCredential(authCredential).await() // .await() -> allows the coroutine to wait for the result of the authentication operation before proceeding
                        // Send the successful result to the callback flow
                        trySend(Result.success(authResult))
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e("GoogleSignIn", "Failed to parse Google ID Token", e)
                    }
                } else {
                    throw RuntimeException("Invalid Google ID Token")
                }
            } catch (e: GetCredentialCancellationException) {
                // Handle sign-in cancellation
                trySend(Result.failure(Exception("Sign-in cancelled")))
            } catch (e: Exception) {
                // Handle other exceptions
                trySend(Result.failure(e))
            }

            // Close the callback flow
            awaitClose { /* Do nothing */ }
        }
    }

    fun signout(context: Context) {
        val currentUser = auth.currentUser
        currentUser?.let { user ->
            val provider = user.providerId ?: ""
            when (provider) {
                "google.com" -> {
                    viewModelScope.launch {
                        logout(context)
                    }
                }
                else -> {
                    auth.signOut()
                }
            }
        }
        _authState.value = AuthState.Unauthenticated
        user.value = User("", "", "", "", "", "")
    }

    private suspend fun logout(context: Context) {
        val credentialManager: CredentialManager = CredentialManager.create(context)

        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Toast.makeText(
                context,
                "Signed out successfully",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Failed to sign out: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    suspend fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            try {
                val authCredentialResult = getGoogleAuthCredentials(context)
                if (authCredentialResult.isSuccess) {
                    val authCredential = authCredentialResult.getOrNull()
                    if (authCredential != null) {
                        val authResult = auth.signInWithCredential(authCredential).await()
                        Result.success(authResult.user != null)
                    } else {
                        Result.failure(Exception("Failed to get Google Auth Credential"))
                    }
                } else {
                    Result.failure(authCredentialResult.exceptionOrNull()!!)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }.onSuccess { result ->
                if (result) {
                    _authState.value = AuthState.Authenticated
                } else {
                    _authState.value = AuthState.Error("Failed to sign in with Google")
                }
            }.onFailure { e ->
                _authState.value = AuthState.Error(e.message ?: "An unknown error occurred")
            }
        }
    }


     private suspend fun getGoogleAuthCredentials(context: Context): Result<AuthCredential?> {
        return try {
            val credentialManager: CredentialManager = CredentialManager.create(context)

            val nonce = UUID.randomUUID().toString()
            val signInWithGoogle: GetSignInWithGoogleOption = GetSignInWithGoogleOption
                .Builder(context.getString(R.string.default_web_client_id))
                .setNonce(nonce)
                .build()

            val request: GetCredentialRequest = GetCredentialRequest
                .Builder()
                .addCredentialOption(signInWithGoogle)
                .build()
            val credential = credentialManager.getCredential(context, request).credential
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)

            Result.success(authCredential)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

sealed class AuthState {
    object Authenticated: AuthState()
    object Unauthenticated: AuthState()
    object Loading: AuthState()
    data class Error(val message: String): AuthState()
}