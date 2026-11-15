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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * "[aliasName] is the same artist as [mainName]". Both are artist NAMES, never MediaStore ids,
 * so the table can go into a backup and still work on another phone. Tags and files are not touched.
 */
@Entity(tableName = "artist_aliases")
class ArtistAliasEntity(
    @PrimaryKey
    @ColumnInfo(name = "alias_name")
    val aliasName: String,
    @ColumnInfo(name = "main_name")
    val mainName: String
)
