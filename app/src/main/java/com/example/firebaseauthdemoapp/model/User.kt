package com.example.firebaseauthdemoapp.model

data class User(
    var id: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val country: String = "",
    val subscription: String = "",
)
