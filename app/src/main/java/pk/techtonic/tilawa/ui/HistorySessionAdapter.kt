package pk.techtonic.tilawa.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import pk.techtonic.tilawa.R
import pk.techtonic.tilawa.data.SessionRecord
import pk.techtonic.tilawa.databinding.ItemHistorySessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row in the history list: passage, mode + date, duration + mistake count. */
class HistorySessionAdapter(
    private val items: List<SessionRecord>,
    private val onClick: (SessionRecord) -> Unit
) : RecyclerView.Adapter<HistorySessionAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemHistorySessionBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistorySessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.binding.passageText.text = if (item.mode == SessionActivity.MODE_FREE) {
            context.getString(R.string.history_free_label)
        } else {
            context.getString(
                R.string.history_passage_format,
                SurahData.urduName(item.surah),
                item.fromAyah,
                item.toAyah
            )
        }
        val modeLabel = if (item.mode == SessionActivity.MODE_FREE) {
            context.getString(R.string.history_mode_free)
        } else {
            context.getString(R.string.history_mode_mem)
        }
        val dateText = SimpleDateFormat("d MMM yyyy، h:mm a", Locale("ur", "PK"))
            .format(Date(item.startedAt))
        holder.binding.infoText.text = "$modeLabel — $dateText"
        val durationText = context.getString(
            R.string.history_duration_format,
            item.durationSec / 60,
            item.durationSec % 60
        )
        val mistakesText = context.getString(
            R.string.history_mistakes_format,
            item.mistakeCount
        )
        holder.binding.countsText.text = "$durationText — $mistakesText"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
