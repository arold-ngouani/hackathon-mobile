package com.example.myapplication.network

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

data class NfcRequest(val input: String)
data class NfcResponse(val message: String) // Ajustez en fonction de la structure de la réponse

interface ApiService {
    @POST("auth/read_nfc")
    fun readNfc(@Body request: NfcRequest): Call<NfcResponse>
}