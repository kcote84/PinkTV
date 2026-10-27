package ca.pinktv

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/** Every removal offers an immediate, idempotent restore, including outside the favourites list. */
object FavouriteFeedback {
    fun show(activity: Activity, entry: Entry, added: Boolean, restore: () -> Unit) {
        if(added) Toast.makeText(activity, "Ajouté aux favoris ★", Toast.LENGTH_SHORT).show()
        else if(!activity.isFinishing) AlertDialog.Builder(activity).setTitle("Favori retiré")
            .setMessage("${entry.name} a été retiré de tes favoris.")
            .setPositiveButton("Annuler le retrait") { _, _ -> restore() }
            .setNegativeButton("Terminé", null).show()
    }
}
