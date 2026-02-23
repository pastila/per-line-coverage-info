package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CoverageApiClient(private val apiEndpoint: String, private val bearerToken: String = "") {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun fetchCoverage(): ApiCoverageResponse? {
        return try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiEndpoint))
                .GET()
                .header("Accept", "application/json")
            if (bearerToken.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $bearerToken")
            }
            val request = requestBuilder.build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                Json.decodeFromString<ApiCoverageResponse>(response.body())
            } else {
                null // Handle non-200 responses gracefully
            }
        } catch (e: Exception) {
            null // Handle exceptions gracefully
        }
    }
}