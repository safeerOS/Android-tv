package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import si.safeer.tv.R

/** Izbira in upravljanje zvocnika v omrezju iz zaslonov Glasba in Predvajanje (isti pogovorni okni). */
object ZvocnikIzbira {
    private fun pokazi(a: Activity, g: AlertDialog.Builder): AlertDialog {
        val okno = g.create()
        okno.setOnShowListener { okno.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        okno.show()
        Kontroler.pokazi(okno)
        return okno
    }

    private fun gradnik(a: Activity) = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    fun izberi(a: Activity, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        val iscem = pokazi(a, gradnik(a).setTitle(R.string.zvocnik_predvajaj_na).setMessage(R.string.zvocnik_iscem)
            .setNegativeButton(a.getString(R.string.os_preklici), null))
        Zvocniki.najdi(a) { najdeni ->
            if (!iscem.isShowing || a.isFinishing) return@najdi
            iscem.dismiss()
            if (najdeni.isEmpty()) {
                pokazi(a, gradnik(a).setTitle(R.string.zvocnik_predvajaj_na).setMessage(R.string.zvocnik_ni_najdenih)
                    .setNegativeButton(a.getString(R.string.os_preklici), null))
                return@najdi
            }
            pokazi(a, gradnik(a).setTitle(R.string.zvocnik_predvajaj_na)
                .setItems(najdeni.map { z -> if (z.model.isNotBlank() && z.model != z.ime) "${z.ime} · ${z.model}" else z.ime }.toTypedArray()) { _, i ->
                    val z = najdeni[i]
                    Zvocniki.tujVir(z) { vir ->
                        if (a.isFinishing) return@tujVir
                        if (vir == null) poslji(a, z, sk, spremenjeno)
                        else pokazi(a, gradnik(a).setTitle(z.ime).setMessage(a.getString(R.string.zvocnik_zaseden, z.ime, vir))
                            .setPositiveButton(R.string.zvocnik_preklopi) { _, _ -> poslji(a, z, sk, spremenjeno) }
                            .setNegativeButton(a.getString(R.string.os_preklici), null))
                    }
                }
                .setNegativeButton(a.getString(R.string.os_preklici), null))
        }
    }

    private fun poslji(a: Activity, z: DlnaPravila.Zvocnik, sk: Jamendo.Skladba, spremenjeno: () -> Unit) {
        Zvocniki.predvajaj(a, z, sk) { napaka ->
            if (napaka != null) {
                Toast.makeText(a, a.getString(R.string.zvocnik_napaka, z.ime), Toast.LENGTH_LONG).show()
            } else {
                GlasbaStoritev.predvajalnik?.pause()      // ena glasba naenkrat: igra zvocnik, ne ta naprava
                Toast.makeText(a, a.getString(R.string.zvocnik_igra, z.ime), Toast.LENGTH_SHORT).show()
            }
            spremenjeno()
        }
    }

    fun upravljaj(a: Activity, spremenjeno: () -> Unit) {
        val z = Zvocniki.aktivni ?: return
        val dejanja = listOf<Pair<String, () -> Unit>>(
            a.getString(R.string.zvocnik_premor) to { Zvocniki.premorAliNadaljuj {} },
            a.getString(R.string.zvocnik_glasneje) to { Zvocniki.glasnost(5) {} },
            a.getString(R.string.zvocnik_tisje) to { Zvocniki.glasnost(-5) {} },
            a.getString(R.string.zvocnik_vrni) to {
                Zvocniki.ustavi { spremenjeno() }
                GlasbaStoritev.predvajalnik?.play()
            },
        )
        pokazi(a, gradnik(a).setTitle(a.getString(R.string.zvocnik_na, z.ime))
            .setItems(dejanja.map { it.first }.toTypedArray()) { _, i -> dejanja[i].second() }
            .setNegativeButton(a.getString(R.string.os_preklici), null))
    }
}
