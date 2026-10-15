package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalSearchData(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("results") val results: List<DizipalSearchResult>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalSearchResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("rating") val rating: String? = null
)
