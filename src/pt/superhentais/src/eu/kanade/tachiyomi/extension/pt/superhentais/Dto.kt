package eu.kanade.tachiyomi.extension.pt.superhentais

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PaginatorResponse(
    val codigo: Int,
    @SerialName("total_page") val totalPage: Int,
    val body: List<String>,
)

@Serializable
class PaginatorFilters(
    @SerialName("filter_data") private val filterData: String,
    @SerialName("filter_genre_add") private val includedGenres: List<String>,
    @SerialName("filter_genre_del") private val excludedGenres: List<String>,
)
