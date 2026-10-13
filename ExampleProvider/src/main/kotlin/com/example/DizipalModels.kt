package com.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalSearchItem(
    @JsonProperty("object_id")
    val id: Int? = null,

    @JsonProperty("object_name")
    val title: String? = null,

    @JsonProperty("object_alternative_name")
    val trTitle: String? = null,

    @JsonProperty("object_poster_url")
    val poster: String? = null,

    @JsonProperty("object_categories")
    val genres: String? = null,

    @JsonProperty("object_release_year")
    val year: Int? = null,

    @JsonProperty("used_type")
    val type: String? = null,

    @JsonProperty("used_slug")
    val slug: String? = null
)
