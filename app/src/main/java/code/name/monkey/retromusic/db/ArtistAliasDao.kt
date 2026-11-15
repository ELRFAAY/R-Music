/*
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Blocking on purpose: the artist repository and the backup code call it off the main thread. */
@Dao
interface ArtistAliasDao {
    @Query("SELECT * FROM artist_aliases")
    fun all(): List<ArtistAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(items: List<ArtistAliasEntity>)

    @Query("DELETE FROM artist_aliases WHERE alias_name = :aliasName")
    fun delete(aliasName: String)

    @Query("DELETE FROM artist_aliases WHERE main_name = :mainName")
    fun deleteGroup(mainName: String)

    @Query("UPDATE artist_aliases SET main_name = :newMain WHERE main_name = :oldMain")
    fun repoint(oldMain: String, newMain: String)
}
