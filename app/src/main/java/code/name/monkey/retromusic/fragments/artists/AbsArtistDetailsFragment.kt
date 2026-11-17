package code.name.monkey.retromusic.fragments.artists

import android.graphics.Color
import android.os.Bundle
import android.text.Spanned
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.album.HorizontalAlbumAdapter
import code.name.monkey.retromusic.adapter.song.SimpleSongAdapter
import code.name.monkey.retromusic.databinding.FragmentArtistDetailsBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.SingleColorTarget
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.network.Result
import code.name.monkey.retromusic.network.model.LastFmArtist
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.*
import com.bumptech.glide.Glide
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import java.util.*

abstract class AbsArtistDetailsFragment : AbsMainActivityFragment(R.layout.fragment_artist_details),
    IAlbumClickListener {
    private var _binding: FragmentArtistDetailsBinding? = null
    private val binding get() = _binding!!

    abstract val detailsViewModel: ArtistDetailsViewModel
    abstract val artistId: Long?
    abstract val artistName: String?
    private lateinit var artist: Artist
    private val artistMerger = ArtistMerger(this)
    private lateinit var songAdapter: SimpleSongAdapter
    private lateinit var albumAdapter: HorizontalAlbumAdapter
    private var forceDownload: Boolean = false
    private var lang: String? = null
    private var biography: Spanned? = null

    private val savedSongSortOrder: String
        get() = PreferenceUtil.artistDetailSongSortOrder
    private val savedAlbumSortOrder: String
        get() = PreferenceUtil.artistAlbumSortOrder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentArtistDetailsBinding.bind(view)
        mainActivity.addMusicServiceEventListener(detailsViewModel)
        mainActivity.setSupportActionBar(binding.toolbar)
        binding.toolbar.title = null
        binding.artistCoverContainer.transitionName = (artistId ?: artistName).toString()
        postponeEnterTransition()
        detailsViewModel.getArtist().observe(viewLifecycleOwner) {
            view.doOnPreDraw {
                startPostponedEnterTransition()
            }
            showArtist(it)
        }
        setupRecyclerView()

        binding.fragmentArtistContent.playAction.apply {
            setOnClickListener { MusicPlayerRemote.openQueue(artist.sortedSongs, 0, true) }
        }
        binding.fragmentArtistContent.shuffleAction.apply {
            setOnClickListener { MusicPlayerRemote.openAndShuffleQueue(artist.songs, true) }
        }

        binding.fragmentArtistContent.biographyText.setOnClickListener {
            if (binding.fragmentArtistContent.biographyText.maxLines == 4) {
                binding.fragmentArtistContent.biographyText.maxLines = Integer.MAX_VALUE
            } else {
                binding.fragmentArtistContent.biographyText.maxLines = 4
            }
        }
        setupSongSortButton()
        setupSongViewButton()
        setupAlbumSortButton()
        binding.appBarLayout?.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun setupRecyclerView() {
        albumAdapter = HorizontalAlbumAdapter(requireActivity(), ArrayList(), this)
        binding.fragmentArtistContent.albumRecyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = GridLayoutManager(this.context, 1, GridLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }
        // item_list shows the cover of every song; item_song only shows the track number, which
        // makes no sense across several albums.
        applySongView()
    }

    private fun songItemLayout(): Int = when (PreferenceUtil.artistDetailSongStyle) {
        1 -> R.layout.item_grid
        2 -> R.layout.item_card
        3 -> R.layout.item_card_color
        4 -> R.layout.item_grid_circle
        else -> R.layout.item_list
    }

    /** Builds the songs list with the style / columns the user picked for artist pages. */
    private fun applySongView() {
        val isList = PreferenceUtil.artistDetailSongStyle == 0
        songAdapter = SimpleSongAdapter(
            requireActivity(), ArrayList(), songItemLayout(), showAlbumAsSubtitle = true
        )
        binding.fragmentArtistContent.recyclerView.apply {
            itemAnimator = DefaultItemAnimator()
            layoutManager = if (isList) LinearLayoutManager(this.context)
            else GridLayoutManager(this.context, PreferenceUtil.artistDetailSongColumns)
            adapter = songAdapter
        }
        if (::artist.isInitialized) {
            allSongsShown = false
            showArtist(artist)
        }
    }

    private fun setupSongViewButton() {
        binding.fragmentArtistContent.songViewMode.setOnClickListener {
            PopupMenu(requireContext(), binding.fragmentArtistContent.songViewMode).apply {
                inflate(R.menu.menu_artist_songs_view)
                val style = PreferenceUtil.artistDetailSongStyle
                val cols = PreferenceUtil.artistDetailSongColumns
                menu.findItem(
                    when (style) {
                        1 -> when (cols) {
                            3 -> R.id.view_grid_3
                            4 -> R.id.view_grid_4
                            else -> R.id.view_grid_2
                        }
                        2 -> R.id.view_card
                        3 -> R.id.view_card_color
                        4 -> R.id.view_circular
                        else -> R.id.view_list
                    }
                ).isChecked = true
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.view_list -> PreferenceUtil.artistDetailSongStyle = 0
                        R.id.view_grid_2, R.id.view_grid_3, R.id.view_grid_4 -> {
                            PreferenceUtil.artistDetailSongStyle = 1
                            PreferenceUtil.artistDetailSongColumns = when (item.itemId) {
                                R.id.view_grid_3 -> 3
                                R.id.view_grid_4 -> 4
                                else -> 2
                            }
                        }
                        R.id.view_card -> PreferenceUtil.artistDetailSongStyle = 2
                        R.id.view_card_color -> PreferenceUtil.artistDetailSongStyle = 3
                        R.id.view_circular -> PreferenceUtil.artistDetailSongStyle = 4
                    }
                    applySongView()
                    true
                }
                show()
            }
        }
    }

    private var allSongsShown = false

    private fun showArtist(artist: Artist) {
        if (artist.songCount == 0) {
            findNavController().navigateUp()
            return
        }
        this.artist = artist
        loadArtistImage(artist)
        if (PreferenceUtil.isAllowedToDownloadMetadata(requireContext())) {
            loadBiography(artist.name)
        }
        binding.artistTitle.text = artist.name
        binding.text.text = String.format(
            "%s • %s",
            MusicUtil.getArtistInfoString(requireContext(), artist),
            MusicUtil.getReadableDurationString(MusicUtil.getTotalDuration(artist.songs))
        )
        val songText = resources.getQuantityString(
            R.plurals.albumSongs, artist.songCount, artist.songCount
        )
        val albumText = resources.getQuantityString(
            R.plurals.albums, artist.songCount, artist.songCount
        )
        binding.fragmentArtistContent.songTitle.text = songText
        binding.fragmentArtistContent.albumTitle.text = albumText
        // Hundreds of rows with covers inside a scrolling page froze the screen: show the first
        // ones and let the user expand the rest.
        val allSongs = artist.sortedSongs
        val showAll = binding.fragmentArtistContent.showAllSongs
        if (!allSongsShown && allSongs.size > FIRST_SONGS) {
            songAdapter.swapDataSet(allSongs.take(FIRST_SONGS))
            showAll.text = getString(R.string.show_all_songs, allSongs.size)
            showAll.isVisible = true
            showAll.setOnClickListener {
                allSongsShown = true
                showAll.isVisible = false
                songAdapter.swapDataSet(allSongs)
            }
        } else {
            showAll.isVisible = false
            songAdapter.swapDataSet(allSongs)
        }
        albumAdapter.swapDataSet(artist.albums)
    }

    private fun loadBiography(
        name: String,
        lang: String? = Locale.getDefault().language,
    ) {
        biography = null
        this.lang = lang
        detailsViewModel.getArtistInfo(name, lang, null).observe(viewLifecycleOwner) { result ->
            when (result) {
                is Result.Loading -> logD("Loading")
                is Result.Error -> logE("Error")
                is Result.Success -> artistInfo(result.data)
            }
        }
    }

    private fun artistInfo(lastFmArtist: LastFmArtist?) {
        if (lastFmArtist != null && lastFmArtist.artist != null && lastFmArtist.artist.bio != null) {
            val bioContent = lastFmArtist.artist.bio.content
            if (bioContent != null && bioContent.trim().isNotEmpty()) {
                binding.fragmentArtistContent.run {
                    biographyText.isVisible = true
                    biographyTitle.isVisible = true
                    biography = bioContent.parseAsHtml()
                    biographyText.text = biography
                    if (lastFmArtist.artist.stats.listeners.isNotEmpty()) {
                        listeners.show()
                        listenersLabel.show()
                        scrobbles.show()
                        scrobblesLabel.show()
                        listeners.text =
                            RetroUtil.formatValue(lastFmArtist.artist.stats.listeners.toFloat())
                        scrobbles.text =
                            RetroUtil.formatValue(lastFmArtist.artist.stats.playcount.toFloat())
                    }
                }
            }
        }

        // If the "lang" parameter is set and no biography is given, retry with default language
        if (biography == null && lang != null) {
            loadBiography(artist.name, null)
        }
    }

    private fun loadArtistImage(artist: Artist) {
        Glide.with(requireContext()).asBitmapPalette().artistImageOptions(artist)
            .load(RetroGlideExtension.getArtistModel(artist)).dontAnimate()
            .into(object : SingleColorTarget(binding.image) {
                override fun onColorReady(color: Int) {
                    setColors(color)
                }
            })
    }

    private fun setColors(color: Int) {
        if (_binding != null) {
            binding.fragmentArtistContent.shuffleAction.applyColor(color)
            binding.fragmentArtistContent.playAction.applyOutlineColor(color)
        }
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        findNavController().navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            null,
            FragmentNavigatorExtras(
                view to albumId.toString()
            )
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return handleSortOrderMenuItem(item)
    }

    /** Pick an existing artist (or type a new name): all songs of this artist get that name. */
    private fun pickArtistImage() {
        selectImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun resetArtistImage() {
        showToast(resources.getString(R.string.updating))
        lifecycleScope.launch {
            CustomArtistImageUtil.getInstance(requireContext())
                .resetCustomArtistImage(artist)
            detailsViewModel.refreshArtistInfo()
        }
        forceDownload = true
    }

    private fun showEditArtistDialog() {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(ctx).apply {
            setText(artist.name)
            setSingleLine()
            hint = getString(R.string.edit_artist_name)
            setSelection(text.length)
        }
        val note = android.widget.TextView(ctx).apply {
            text = getString(R.string.edit_artist_name_note)
            textSize = 12f
            alpha = 0.7f
        }
        val change = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(R.string.edit_artist_change_image)
            setOnClickListener { pickArtistImage() }
        }
        val remove = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(R.string.edit_artist_remove_image)
            setOnClickListener { resetArtistImage() }
        }
        val holder = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(note)
            addView(change)
            addView(remove)
        }
        materialDialog(R.string.edit_artist_title)
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != artist.name) {
                    artistMerger.merge(artist.songs, newName) {
                        libraryViewModel.forceReload(ReloadType.Artists)
                        findNavController().navigateUp()
                    }
                }
            }
            .show()
    }

    private fun showMergeArtistDialog() {
        val names = (libraryViewModel.getArtists().value ?: emptyList())
            .map { it.name }
            .filter { it != artist.name }
            .distinct()
            .sorted()
        val input = android.widget.AutoCompleteTextView(requireContext()).apply {
            setAdapter(
                android.widget.ArrayAdapter(
                    requireContext(), android.R.layout.simple_dropdown_item_1line, names
                )
            )
            threshold = 1
            setSingleLine()
            hint = getString(R.string.merge_artist_hint)
        }
        val holder = android.widget.FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        materialDialog(R.string.merge_artist)
            .setMessage(getString(R.string.merge_artist_message, artist.name))
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.merge_artist_action) { _, _ ->
                val target = input.text.toString().trim()
                if (target.isNotEmpty() && target != artist.name) {
                    artistMerger.merge(artist.songs, target) {
                        libraryViewModel.forceReload(ReloadType.Artists)
                        findNavController().navigateUp()
                    }
                }
            }
            .show()
    }

    private fun handleSortOrderMenuItem(item: MenuItem): Boolean {
        val songs = artist.songs
        when (item.itemId) {
            android.R.id.home -> findNavController().navigateUp()
            R.id.action_play_next -> {
                MusicPlayerRemote.playNext(songs)
                return true
            }

            R.id.action_add_to_current_playing -> {
                MusicPlayerRemote.enqueue(songs)
                return true
            }

            R.id.action_merge_artist -> {
                showMergeArtistDialog()
                return true
            }

            R.id.action_add_to_playlist -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val playlists = get<RealRepository>().fetchPlaylists()
                    withContext(Dispatchers.Main) {
                        AddToPlaylistDialog.create(playlists, songs)
                            .show(childFragmentManager, "ADD_PLAYLIST")
                    }
                }
                return true
            }

            R.id.action_edit_artist -> {
                showEditArtistDialog()
                return true
            }
        }
        return true
    }

    private fun setupSongSortButton() {
        binding.fragmentArtistContent.songSortOrder.setOnClickListener {
            PopupMenu(requireContext(), binding.fragmentArtistContent.songSortOrder).apply {
                inflate(R.menu.menu_artist_song_sort_order)
                setUpSortOrderMenu(menu)
                setOnMenuItemClickListener { item ->
                    val sortOrder = when (item.itemId) {
                        R.id.action_sort_order_title -> SortOrder.ArtistSongSortOrder.SONG_A_Z
                        R.id.action_sort_order_title_desc -> SortOrder.ArtistSongSortOrder.SONG_Z_A
                        R.id.action_sort_order_album -> SortOrder.ArtistSongSortOrder.SONG_ALBUM
                        R.id.action_sort_order_year -> SortOrder.ArtistSongSortOrder.SONG_YEAR
                        R.id.action_sort_order_song_duration -> SortOrder.ArtistSongSortOrder.SONG_DURATION
                        else -> {
                            throw IllegalArgumentException("invalid ${item.title}")
                        }
                    }
                    item.isChecked = true
                    setSaveSortOrder(sortOrder)
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    private fun setSaveSortOrder(sortOrder: String) {
        PreferenceUtil.artistDetailSongSortOrder = sortOrder
        songAdapter.swapDataSet(artist.sortedSongs)
    }

    private fun setupAlbumSortButton() {
        binding.fragmentArtistContent.albumSortOrder.setOnClickListener {
            PopupMenu(requireContext(), binding.fragmentArtistContent.albumSortOrder).apply {
                inflate(R.menu.menu_artist_album_sort_order)
                setUpAlbumSortOrderMenu(menu)
                setOnMenuItemClickListener { item ->
                    val sortOrder = when (item.itemId) {
                        R.id.action_sort_order_title -> SortOrder.ArtistAlbumSortOrder.ALBUM_A_Z
                        R.id.action_sort_order_title_desc -> SortOrder.ArtistAlbumSortOrder.ALBUM_Z_A
                        R.id.action_sort_order_year -> SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR_ASC
                        R.id.action_sort_order_year_desc -> SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR
                        else -> {
                            throw IllegalArgumentException("invalid ${item.title}")
                        }
                    }
                    item.isChecked = true
                    setSaveAlbumSortOrder(sortOrder)
                    return@setOnMenuItemClickListener true
                }
                show()
            }
        }
    }

    private fun setSaveAlbumSortOrder(sortOrder: String) {
        PreferenceUtil.artistAlbumSortOrder = sortOrder
        albumAdapter.swapDataSet(artist.sortedAlbums)
    }

    private fun setUpAlbumSortOrderMenu(sortOrder: Menu) {
        when (savedAlbumSortOrder) {
            SortOrder.ArtistAlbumSortOrder.ALBUM_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked =
                true

            SortOrder.ArtistAlbumSortOrder.ALBUM_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked =
                true

            SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR_ASC -> sortOrder.findItem(R.id.action_sort_order_year).isChecked =
                true

            SortOrder.ArtistAlbumSortOrder.ALBUM_YEAR -> sortOrder.findItem(R.id.action_sort_order_year_desc).isChecked =
                true

            else -> {
                throw IllegalArgumentException("invalid $savedAlbumSortOrder")
            }
        }
    }

    private fun setUpSortOrderMenu(sortOrder: Menu) {
        when (savedSongSortOrder) {
            SortOrder.ArtistSongSortOrder.SONG_A_Z -> sortOrder.findItem(R.id.action_sort_order_title).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_Z_A -> sortOrder.findItem(R.id.action_sort_order_title_desc).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_ALBUM -> sortOrder.findItem(R.id.action_sort_order_album).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_YEAR -> sortOrder.findItem(R.id.action_sort_order_year).isChecked =
                true

            SortOrder.ArtistSongSortOrder.SONG_DURATION -> sortOrder.findItem(R.id.action_sort_order_song_duration).isChecked =
                true

            else -> {
                throw IllegalArgumentException("invalid $savedSongSortOrder")
            }
        }
    }

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            lifecycleScope.launch {
                if (uri != null) {
                    CustomArtistImageUtil.getInstance(requireContext())
                        .setCustomArtistImage(artist, uri)

                    detailsViewModel.refreshArtistInfo()
                }
            }
        }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_artist_detail, menu)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
private const val FIRST_SONGS = 30
