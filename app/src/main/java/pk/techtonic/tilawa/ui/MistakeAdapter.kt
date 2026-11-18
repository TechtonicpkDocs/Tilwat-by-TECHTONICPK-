package pk.techtonic.tilawa.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import pk.techtonic.tilawa.R
import pk.techtonic.tilawa.databinding.ItemMistakeBinding

/** Display model for one mistake, shared by the live session list and history. */
data class MistakeItem(
    val surah: Int,
    val ayah: Int,
    val wordIndex: Int,
    val errorClass: String,
    val expected: String,
    val observed: String
)

/**
 * Mistake list. Colour per error class: wrong word red, omitted amber,
 * added blue, skipped passage purple. Word indices are 0-based in the
 * engine, so the shown word number is wordIndex + 1.
 */
class MistakeAdapter(private val items: List<MistakeItem>) :
    RecyclerView.Adapter<MistakeAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemMistakeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMistakeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val (labelRes, chipColorRes, cardColorRes) = when (item.errorClass) {
            "wrong_word" -> Triple(
                R.string.mistake_wrong_word, R.color.mistake_red, R.color.mistake_red_bg
            )
            "omitted_word" -> Triple(
                R.string.mistake_omitted_word, R.color.mistake_amber, R.color.mistake_amber_bg
            )
            "added_word" -> Triple(
                R.string.mistake_added_word, R.color.mistake_blue, R.color.mistake_blue_bg
            )
            "skipped_passage" -> Triple(
                R.string.mistake_skipped_passage, R.color.mistake_purple, R.color.mistake_purple_bg
            )
            else -> Triple(
                R.string.mistake_unknown, R.color.text_secondary, R.color.background
            )
        }
        holder.binding.classChip.setText(labelRes)
        holder.binding.classChip.setBackgroundColor(
            ContextCompat.getColor(context, chipColorRes)
        )
        holder.binding.classChip.setTextColor(
            ContextCompat.getColor(context, R.color.white)
        )
        holder.binding.mistakeCard.setCardBackgroundColor(
            ContextCompat.getColor(context, cardColorRes)
        )
        val surahName = SurahData.urduName(item.surah)
        holder.binding.locationText.text = context.getString(
            R.string.mistake_location_format, surahName, item.ayah, item.wordIndex + 1
        )
        holder.binding.expectedText.text = context.getString(
            R.string.mistake_expected_format, item.expected
        )
        holder.binding.observedText.text = context.getString(
            R.string.mistake_observed_format, item.observed
        )
    }

    override fun getItemCount(): Int = items.size
}
