package ru.netology.nmedia.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ru.netology.nmedia.api.ApiService
import ru.netology.nmedia.dao.PostDao
import ru.netology.nmedia.dao.PostRemoteKeyDao
import ru.netology.nmedia.db.AppDb
import ru.netology.nmedia.entity.PostEntity
import ru.netology.nmedia.entity.PostRemoteKeyEntity
import ru.netology.nmedia.error.ApiError
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
class PostRemoteMediator(
    private val apiService: ApiService,
    private val postDao: PostDao,
    private val postRemoteKeyDao: PostRemoteKeyDao,
    private val appDb: AppDb,
) : RemoteMediator<Int, PostEntity>() {
//    override suspend fun initialize(): InitializeAction = InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PostEntity>
    ): MediatorResult {
        try {
            val result = when (loadType) {
                LoadType.REFRESH -> postRemoteKeyDao.max()?.let {
                    apiService.getAfter(it, count = state.config.pageSize)
                } ?: apiService.getLatest(state.config.initialLoadSize)

                LoadType.APPEND -> {
                    val id = postRemoteKeyDao.min() ?: return MediatorResult.Success(false)
                    apiService.getBefore(id = id, count = state.config.pageSize)
                }

                LoadType.PREPEND -> {
                    return MediatorResult.Success(true)
                }
            }
            if (!result.isSuccessful) {
                throw ApiError(result.code(), result.message())
            }

            val data = result.body().orEmpty()

            appDb.withTransaction {
                when (loadType) {
                    LoadType.REFRESH -> {
                        postRemoteKeyDao.insert(
                            PostRemoteKeyEntity(
                                PostRemoteKeyEntity.KeyType.AFTER,
                                data.first().id
                            )
                        )
                        if (postDao.isEmpty()) {
                            postRemoteKeyDao.insert(
                                PostRemoteKeyEntity(
                                    PostRemoteKeyEntity.KeyType.BEFORE,
                                    data.last().id
                                )
                            )
                        }
                    }

                    LoadType.APPEND -> {
                        postRemoteKeyDao.insert(
                            PostRemoteKeyEntity(
                                PostRemoteKeyEntity.KeyType.BEFORE,
                                data.last().id
                            )
                        )
                    }

                    // LoadType.PREPEND
                    else -> Unit
                }
                postDao.insert(data.map { PostEntity.fromDto(it) })
            }

            return MediatorResult.Success(data.isEmpty())
        } catch (e: IOException) {
            return MediatorResult.Error(e)
        }

    }

}