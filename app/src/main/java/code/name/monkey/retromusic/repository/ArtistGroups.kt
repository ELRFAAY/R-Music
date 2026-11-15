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
package code.name.monkey.retromusic.repository

import code.name.monkey.retromusic.db.ArtistAliasDao
import code.name.monkey.retromusic.db.ArtistAliasEntity
import java.util.Locale

/**
 * Lets the user say "these artist names are one artist" without touching any tag or file.
 * Everything is stored by artist NAME (see [ArtistAliasEntity]); names are compared ignoring
 * case and surrounding spaces. All functions are blocking, call them off the main thread.
 */
class ArtistGroups(private val dao: ArtistAliasDao) {

    /** A read-only picture of the table, so one list build sees one consistent state. */
    class Snapshot(private val rows: List<ArtistAliasEntity>) {
        private val mainOf = rows.associate { key(it.aliasName) to it.mainName }
        private val mainKeys = rows.map { key(it.mainName) }.toSet()

        val isEmpty: Boolean get() = rows.isEmpty()

        fun isAlias(name: String?): Boolean = name != null && mainOf.containsKey(key(name))

        /** True for an alias and for a main name that has aliases. */
        fun isGrouped(name: String?): Boolean =
            name != null && (isAlias(name) || key(name) in mainKeys)

        /** The name [name] is shown as: its main artist when it is an alias, else itself. */
        fun displayName(name: String): String = mainOf[key(name)] ?: name

        /** The same text for every member of a group, null for an artist that is not grouped. */
        fun groupKey(name: String?): String? =
            if (name != null && isGrouped(name)) "g:" + key(displayName(name)) else null

        /** The main name followed by all its aliases, with the spelling stored in the table. */
        fun membersOf(name: String): List<String> {
            val main = displayName(name)
            val mainKey = key(main)
            return listOf(main) + rows.filter { key(it.mainName) == mainKey }.map { it.aliasName }
        }
    }

    fun snapshot(): Snapshot = Snapshot(dao.all())

    /** Names grouped under [mainName], without the main name itself. */
    fun aliasesOf(mainName: String): List<String> =
        snapshot().membersOf(mainName).drop(1)

    /**
     * Groups [aliasNames] under [mainName]. If [mainName] is itself an alias, its own main artist
     * is used, so there are never chains. Groups of the names that are merged move along.
     */
    fun merge(mainName: String, aliasNames: Collection<String>) {
        val snap = snapshot()
        val root = snap.displayName(mainName)
        val rows = mutableListOf<ArtistAliasEntity>()
        for (name in aliasNames) {
            val nameRoot = snap.displayName(name)
            if (key(nameRoot) == key(root)) continue
            // Everything that pointed at the merged artist now points at the new main artist.
            dao.repoint(nameRoot, root)
            rows += ArtistAliasEntity(nameRoot, root)
            if (key(name) != key(nameRoot)) rows += ArtistAliasEntity(name, root)
        }
        if (rows.isNotEmpty()) dao.upsert(rows)
    }

    /** Takes one name out of its group. */
    fun unmerge(aliasName: String) = dao.delete(aliasName)

    /** Dissolves the whole group of [mainName]. */
    fun unmergeAll(mainName: String) = dao.deleteGroup(snapshot().displayName(mainName))

    /** For the backup. */
    fun allRows(): List<ArtistAliasEntity> = dao.all()

    /** For the restore: adds or updates the rows, existing ones that are not in the list stay. */
    fun restoreRows(rows: List<ArtistAliasEntity>) {
        if (rows.isNotEmpty()) dao.upsert(rows)
    }

    companion object {
        fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
