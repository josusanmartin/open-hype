package dev.josu.hypecar.core.network

import dev.josu.hypecar.core.network.dto.BlogDto
import dev.josu.hypecar.core.network.dto.GetTokenResponseDto
import dev.josu.hypecar.core.network.dto.TrackDto
import dev.josu.hypecar.core.network.dto.UserDto
import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

interface HypeApiService {
    @FormUrlEncoded
    @POST("get_token")
    suspend fun getToken(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("device_id") deviceId: String,
    ): GetTokenResponseDto

    @GET("tracks")
    suspend fun tracks(
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @GET("tracks/{trackId}")
    suspend fun track(
        @Path("trackId") trackId: String,
    ): TrackDto

    @GET("popular")
    suspend fun popular(
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @GET("me/favorites")
    suspend fun favorites(
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @FormUrlEncoded
    @POST("me/favorites")
    suspend fun toggleFavorite(
        @Field("val") value: String,
        @Field("type") type: String = "item",
    ): ResponseBody

    @GET("me/playlist_names")
    suspend fun playlistNames(): List<String>

    @GET("me/playlists/{playlistId}")
    suspend fun playlist(
        @Path("playlistId") playlistId: Int,
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @GET("me/feed")
    suspend fun feed(
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @FormUrlEncoded
    @POST("me/history")
    suspend fun postHistory(
        @Field("type") type: String = "listen",
        @Field("itemid") itemId: String,
        @Field("pos") position: Int,
    ): ResponseBody

    @GET("blogs")
    suspend fun blogs(
        @QueryMap params: Map<String, String>,
    ): List<BlogDto>

    @GET("blogs/{blogId}")
    suspend fun blog(
        @Path("blogId") blogId: Int,
    ): BlogDto

    @GET("blogs/{blogId}/tracks")
    suspend fun blogTracks(
        @Path("blogId") blogId: Int,
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @GET("users/{username}")
    suspend fun user(
        @Path("username") username: String,
    ): UserDto

    @GET("users/{username}/favorites")
    suspend fun userFavorites(
        @Path("username") username: String,
        @QueryMap params: Map<String, String>,
    ): List<TrackDto>

    @GET("users/{username}/friends")
    suspend fun userFriends(
        @Path("username") username: String,
        @QueryMap params: Map<String, String>,
    ): List<UserDto>
}
