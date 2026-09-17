package dev.daymond.socialcookies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import dev.daymond.socialcookies.databinding.ItemCookieSheetBinding

class SheetAdapter(private val cookies: MutableList<CookieRecord>) :
    RecyclerView.Adapter<SheetAdapter.ViewHolder>() {

    var onStatusToggle: ((CookieRecord) -> Unit)? = null
    private val visiblePasswordPositions = mutableSetOf<Int>()

    class ViewHolder(val binding: ItemCookieSheetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCookieSheetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cookie = cookies[position]
        holder.binding.tvUid.text = cookie.uid
        holder.binding.tvPassword.text = cookie.password.ifEmpty { "-" }
        holder.binding.tvCookies.text = cookie.cookies

        // Tap UID to copy to clipboard
        holder.binding.tvUid.setOnClickListener {
            val clipboard =
                holder.itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("UID", cookie.uid)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(holder.itemView.context, "UID Copied: ${cookie.uid}", Toast.LENGTH_SHORT)
                .show()
        }

        val isPasswordVisible = visiblePasswordPositions.contains(position)
        if (isPasswordVisible) {
            // Show password
            holder.binding.tvPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
            holder.binding.btnShowPassword.setImageResource(R.drawable.ic_visibility)
        } else {
            // Hide password
            holder.binding.tvPassword.transformationMethod = PasswordTransformationMethod.getInstance()
            holder.binding.btnShowPassword.setImageResource(R.drawable.ic_visibility_off)
        }
        holder.binding.btnShowPassword.setOnClickListener {
            if(visiblePasswordPositions.contains(position)){
                visiblePasswordPositions.remove(position)
            } else {
                visiblePasswordPositions.add(position)
            }
            notifyItemChanged(position)
        }

        // Live/Dead Status Indicator
        if (cookie.isLive) {
            holder.binding.indicatorStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            holder.binding.indicatorStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F44336"))
        }

        holder.binding.indicatorStatus.setOnClickListener {
            onStatusToggle?.invoke(cookie)
        }
    }

    override fun getItemCount() = cookies.size

    fun updateData(newCookies: List<CookieRecord>) {
        cookies.clear()
        cookies.addAll(newCookies)
        notifyDataSetChanged()
    }

    fun getCookieAt(position: Int): CookieRecord = cookies[position]

    fun removeItem(position: Int) {
        cookies.removeAt(position)
        notifyItemRemoved(position)
    }
}
