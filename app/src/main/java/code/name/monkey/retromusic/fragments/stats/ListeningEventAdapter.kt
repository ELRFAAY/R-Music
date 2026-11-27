package code.name.monkey.retromusic.fragments.stats

import android.graphics.Outline
import android.text.format.DateUtils
import android.view.View
import android.view.ViewOutlineProvider
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.ItemListeningEventBinding
import code.name.monkey.retromusic.db.ListeningEventEntity
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.util.MusicUtil
import com.bumptech.glide.Glide

class ListeningEventAdapter :
    ListAdapter<ListeningEventEntity, ListeningEventAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemListeningEventBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // round the cover corners
            binding.cover.clipToOutline = true
            binding.cover.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 8f * view.resources.displayMetrics.density)
                }
            }
        }
    }

    /** A tap on a history row. */
    var onClick: ((ListeningEventEntity) -> Unit)? = null

    /** Set once the library has been read; until then (and for deleted songs) the default cover shows. */
    var songIndex: SongIndex? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemListeningEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        val context = holder.itemView.context
        val date = DateUtils.formatDateTime(
            context,
            event.playedAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
        )
        holder.binding.title.text = event.title
        holder.itemView.setOnClickListener { onClick?.invoke(event) }
        val song = songIndex?.find(event)
        val request = Glide.with(holder.binding.cover).load(
            if (song != null) RetroGlideExtension.getSongModel(song) else R.drawable.default_audio_art
        )
        val styled = if (song != null) {
            request.songCoverOptions(song)
        } else {
            request.placeholder(R.drawable.default_audio_art)
        }
        styled.centerCrop().into(holder.binding.cover)
        holder.binding.subtitle.text = context.getString(
            R.string.listening_event_subtitle,
            event.artistName,
            MusicUtil.getReadableDurationString(event.listenedMs),
            date
        )
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.binding.cover).clear(holder.binding.cover)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ListeningEventEntity>() {
            override fun areItemsTheSame(
                oldItem: ListeningEventEntity,
                newItem: ListeningEventEntity
            ) = oldItem.id == newItem.id

            // Events are immutable once written.
            override fun areContentsTheSame(
                oldItem: ListeningEventEntity,
                newItem: ListeningEventEntity
            ) = oldItem.id == newItem.id
        }
    }
}
