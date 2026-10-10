package miku.moe.app.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

private const val DATA_AGENT = "AnimeXNonton 26.10.4/22"

interface AnimeApiService {
    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api2/get_posts/")
    suspend fun getHomePosts(
        // Keep the same fields and order as the captured official API2 request.
        @Field("page") page: String,
        @Field("count") count: String,
        @Field("isAPKvalid") isApkValid: String = "true"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api2/search_category_collection/")
    suspend fun searchAnime(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("search") search: String,
        @Field("page") page: String,
        @Field("count") count: String,
        @Field("lang") lang: String = "ID"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "User-Agent: okhttp/5.5.0"
    )
    @GET("animexnonton/api/phalcon/api2/get_anime_genre_list/")
    suspend fun getGenreList(): GenreListResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api2/get_anime_by_genre/")
    suspend fun getAnimeByGenre(
        @Field("isAPKvalid") isApkValid: String = "true",
        @Field("genre1") genre1: String,
        @Field("genre2") genre2: String,
        @Field("page") page: String = "1",
        @Field("count") count: String = "20",
        @Field("lang") lang: String = "ID",
        @Field("sort") sort: String = "ASC"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api2/get_category_not_ongoing/")
    suspend fun getAllAnime(
        @Field("page") page: String = "1",
        @Field("count") count: String = "20",
        @Field("lang") lang: String = "ID",
        @Field("isAPKvalid") isApkValid: String = "true"
    ): AnimeApiResponse

    @Headers(
        "Cache-Control: max-age=0",
        "Data-Agent: $DATA_AGENT",
        "Content-Type: application/x-www-form-urlencoded",
        "User-Agent: okhttp/5.5.0"
    )
    @FormUrlEncoded
    @POST("animexnonton/api/phalcon/api2/get_category_ongoing/")
    suspend fun getSchedule(
        @Field("page") page: String = "1",
        @Field("count") count: String = "20",
        @Field("lang") lang: String = "ID",
        @Field("isAPKvalid") isApkValid: String = "true"
    ): AnimeApiResponse
}

data class AnimeApiResponse(
    val status: String? = null,
    val count: Int? = null,
    @SerializedName("count_total") val countTotal: Int? = null,
    val pages: Int? = null,
    val posts: List<ApiAnimePost>? = null,
    val categories: List<ApiAnimePost>? = null
)

data class GenreListResponse(
    val status: String? = null,
    val genre: List<ApiGenre>? = null
)

data class ApiGenre(
    @SerializedName("genre_name") val genreName: String? = null,
    @SerializedName("genre_status_hide") val genreStatusHide: Int? = null
)

data class ApiAnimePost(
    @SerializedName("channel_id") val channelId: Int? = null,
    @SerializedName("category_id") val categoryId: Int? = null,
    val cid: Int? = null,
    @SerializedName("channel_name") val channelName: String? = null,
    @SerializedName("channel_image") val channelImage: String? = null,
    @SerializedName("channel_type") val channelType: String? = null,
    @SerializedName("video_id") val videoId: String? = null,
    @SerializedName("category_name") val categoryName: String? = null,
    @SerializedName("category_image") val categoryImage: String? = null,
    @SerializedName("img_url") val imgUrl: String? = null,
    val created: String? = null,
    val lang: String? = null,
    @SerializedName("safe_images") val safeImages: Boolean? = null,
    @SerializedName("count_view") val countView: String? = null,
    @SerializedName("total_views") val totalViews: String? = null,
    val ongoing: Int? = null,
    @SerializedName("is_hd_available") val isHdAvailable: Boolean? = null,
    @SerializedName("is_fhd_available") val isFhdAvailable: Boolean? = null,
    val rating: String? = null,
    val years: String? = null,
    val days: Int? = null,
    @SerializedName("count_anime") val countAnime: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("delay_status") val delayStatus: Int? = null,
    @SerializedName("delay_reason") val delayReason: String? = null,
    val genre: String? = null
)


/** DTOs grounded in decrypted API2 responses observed in the supplied HAR. */
data class Api2PostDescriptionResponse(
    val status: String? = null,
    @SerializedName("channel_description") val channelDescription: String? = null,
    @SerializedName("channel_name") val channelName: String? = null,
    @SerializedName("channel_image") val channelImage: String? = null,
    @SerializedName("channel_id") val channelId: Int? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("category_name") val categoryName: String? = null,
    @SerializedName("channel_url") val channelUrl: String? = null,
    @SerializedName("channel_url_hd") val channelUrlHd: String? = null,
    @SerializedName("channel_url_fhd") val channelUrlFhd: String? = null,
    @SerializedName("is_hd_available") val isHdAvailable: Boolean? = null,
    @SerializedName("is_fhd_available") val isFhdAvailable: Boolean? = null,
    @SerializedName("gdrive_url") val gdriveUrl: String? = null,
    @SerializedName("episode_url_1") val episodeUrl1: String? = null,
    @SerializedName("episode_url_2") val episodeUrl2: String? = null,
    val lang: String? = null,
    val ongoing: Int? = null,
    @SerializedName("download_url") val downloadUrl: String? = null,
    @SerializedName("allow_download") val allowDownload: Boolean? = null,
    @SerializedName("safe_images") val safeImages: Boolean? = null,
    @SerializedName("img_url") val imgUrl: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val years: Int? = null,
    @SerializedName("count_view") val countView: String? = null,
    @SerializedName("video_auth") val videoAuth: String? = null,
    @SerializedName("secretKey") val secretKey: String? = null
)

data class Api2EpisodeNavigationResponse(
    val status: String? = null,
    @SerializedName("count_total") val countTotal: Int? = null,
    @SerializedName("posts_next_previous") val postsNextPrevious: List<Api2EpisodeNavigation>? = null
)

data class Api2EpisodeNavigation(
    @SerializedName("current_id") val currentId: Int? = null,
    @SerializedName("current_value") val currentValue: String? = null,
    @SerializedName("previous_id") val previousId: Int? = null,
    @SerializedName("previous_value") val previousValue: String? = null,
    @SerializedName("next_id") val nextId: Int? = null,
    @SerializedName("next_value") val nextValue: String? = null
)

data class Api2CategoryPostsResponse(
    val status: String? = null,
    val count: Int? = null,
    @SerializedName("count_total") val countTotal: Int? = null,
    val pages: Int? = null,
    val category: Api2CategoryMetadata? = null,
    val posts: List<ApiAnimePost>? = null
)

data class Api2CategoryMetadata(
    val cid: Int? = null,
    @SerializedName("category_name") val categoryName: String? = null,
    @SerializedName("img_url") val imgUrl: String? = null,
    val ongoing: Int? = null,
    val genre: String? = null,
    val years: String? = null,
    val rating: String? = null,
    @SerializedName("is_fhd_available") val isFhdAvailable: Boolean? = null,
    @SerializedName("is_hd_available") val isHdAvailable: Boolean? = null,
    @SerializedName("safe_images") val safeImages: Boolean? = null
)

data class Api2RatingResponse(
    val status: String? = null,
    val up: Int? = null,
    val down: Int? = null,
    @SerializedName("my_vote") val myVote: Int? = null,
    // The captured API2 response returns a numeric comment count, not a comment array.
    val comments: Int? = null
)

data class Api2HomeInfoResponse(
    val status: String? = null,
    val enable: Boolean? = null,
    val text: String? = null,
    val link: String? = null
)

data class Api2UserCountResponse(
    val status: String? = null,
    val total: Int? = null
)

data class Api2UpdateViewResponse(
    val status: String? = null,
    val message: String? = null,
    @SerializedName("channel_id") val channelId: Int? = null
)

data class Api2CountViewsResponse(
    val status: String? = null,
    val counts: Map<String, Int>? = null
)
